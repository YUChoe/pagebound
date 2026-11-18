package io.github.yuchoe.pagebound.migrator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;
import io.github.yuchoe.pagebound.core.page.PageStore;
import io.github.yuchoe.pagebound.core.page.WikiPaths;
import io.github.yuchoe.pagebound.core.tag.TagNames;
import io.github.yuchoe.pagebound.migrator.ContentConverter.Prepared;
import io.github.yuchoe.pagebound.migrator.WikiJsSource.Asset;

/**
 * Wiki.js → Pagebound 마이그레이션 실행 (spec/features/F8-migration.md).
 * DB 작업은 단일 트랜잭션, 첨부 파일은 data/attachments 에 기록한다.
 */
@Component
class Migrator {

    static final String ORPHAN_SLUG = "미분류 첨부";
    private static final long ADMIN_GROUP = 1;
    private static final long EDITOR_GROUP = 3;

    private final JdbcClient jdbc;
    private final PageStore pages;
    private final LinkIndexer links;
    private final AttachmentStorage storage;
    private final TransactionTemplate tx;
    private final ContentConverter converter = new ContentConverter();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    Migrator(JdbcClient jdbc, PageStore pages, LinkIndexer links, AttachmentStorage storage,
             PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.pages = pages;
        this.links = links;
        this.storage = storage;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 마이그레이션 + 검증 + 리포트. 검증 전부 통과 시 true. */
    boolean run(MigrationOptions options) {
        MigrationReport report = new MigrationReport();
        try (WikiJsSource src = new WikiJsSource(options.source())) {
            tx.executeWithoutResult(status -> migrate(src, report));
            List<MigrationReport.Check> checks = new MigrationVerifier(jdbc, storage).verify(src, report);
            report.write(options.reportFile(), checks);
            return checks.stream().allMatch(MigrationReport.Check::passed);
        }
    }

    private void migrate(WikiJsSource src, MigrationReport report) {
        Set<Long> userIds = migrateUsers(src, report);
        Function<Long, Long> user = id -> id != null && userIds.contains(id) ? id : null;

        List<WikiJsSource.Page> srcPages = src.pages();
        List<Asset> assets = src.assets();
        Map<String, Asset> assetsByPath = new LinkedHashMap<>();
        assets.forEach(a -> assetsByPath.put(a.urlPath(), a));

        Map<Long, Prepared> prepared = new HashMap<>();
        for (WikiJsSource.Page p : srcPages) {
            Prepared pr = converter.prepare(p.content(), assetsByPath.keySet());
            prepared.put(p.id(), pr);
            pr.counts().forEach((k, v) -> report.add(k, v));
            pr.warnings().forEach(w -> report.item("변환 경고", p.path() + ": " + w));
        }

        Map<String, Long> idByPath = insertTree(srcPages, user, report);
        Map<Long, String> pathById = new HashMap<>();
        idByPath.forEach((path, id) -> pathById.put(id, path));

        // 에셋 소유권: 참조 문서마다 첨부 생성(복수 참조는 복제), 미참조는 미분류 문서로
        Map<String, Set<Long>> referencedBy = new LinkedHashMap<>();
        for (WikiJsSource.Page p : srcPages) {
            for (String assetPath : prepared.get(p.id()).assetPaths()) {
                referencedBy.computeIfAbsent(assetPath, k -> new TreeSet<>()).add(p.id());
            }
        }
        report.count("에셋 단일 참조", referencedBy.values().stream().filter(s -> s.size() == 1).count());
        report.count("에셋 복수 참조(복제)", referencedBy.values().stream().filter(s -> s.size() > 1).count());

        Map<Long, Map<String, String>> urlByPageAndAsset = new HashMap<>();
        Map<Long, Set<String>> usedNames = new HashMap<>();
        for (WikiJsSource.Page p : srcPages) {
            for (String assetPath : prepared.get(p.id()).assetPaths()) {
                Asset a = assetsByPath.get(assetPath);
                String url = attach(p.id(), a.filename(), a.mime(), src.assetData(a.id()), user.apply(a.authorId()),
                        a.createdAt(), usedNames, report, a.id());
                urlByPageAndAsset.computeIfAbsent(p.id(), k -> new HashMap<>()).put(assetPath, url);
            }
        }

        List<Asset> orphans = assets.stream().filter(a -> !referencedBy.containsKey(a.urlPath())).toList();
        report.count("에셋 미참조(미분류 첨부)", orphans.size());
        if (!orphans.isEmpty()) {
            insertOrphanPage(src, orphans, user, usedNames, report, idByPath);
        }

        // 본문 렌더링 + 리비전 + alias
        Map<Long, List<WikiJsSource.History>> history = src.history().stream()
                .collect(Collectors.groupingBy(WikiJsSource.History::pageId, LinkedHashMap::new, Collectors.toList()));
        for (WikiJsSource.Page p : srcPages) {
            Prepared pr = prepared.get(p.id());
            Map<String, String> assetUrls = urlByPageAndAsset.getOrDefault(p.id(), Map.of());
            String content = converter.render(pr,
                    a -> assetUrls.get(a.assetPath()),
                    d -> attach(p.id(), "diagram-" + d.index() + ".svg", "image/svg+xml", pr.diagrams().get(d.index() - 1),
                            user.apply(p.authorId()), p.updatedAt(), usedNames, report, null),
                    e -> downloadImage(p, e.url(), user, usedNames, report));
            List<WikiJsSource.History> hs = history.getOrDefault(p.id(), List.of());
            jdbc.sql("UPDATE pages SET content = ?, version = ? WHERE id = ?")
                    .params(content, hs.size() + 1, p.id()).update();
            for (int i = 0; i < hs.size(); i++) {
                WikiJsSource.History h = hs.get(i);
                String action = i == 0 ? "created" : "moved".equals(h.action()) ? "moved" : "updated";
                pages.insertRevision(new PageStore.NewRevision(p.id(), i + 1, h.title(), h.path(), h.content(),
                        action, "migration", null, user.apply(h.authorId()), null, h.versionDate()));
            }
            pages.insertRevision(new PageStore.NewRevision(p.id(), hs.size() + 1, p.title(), p.path(), content,
                    hs.isEmpty() ? "created" : "updated", "migration", null, user.apply(p.authorId()), null,
                    p.updatedAt()));
            pages.insertAlias(p.path(), p.id());
        }
        report.count("원본 이력(현재 문서)", history.values().stream().mapToInt(List::size).sum());
        report.count("원본 이력(삭제 문서, 제외)", src.historyOfDeletedPages());

        migrateTags(src, report);
        links.rebuildAll();
        reportPages(srcPages, report);
    }

    private Set<Long> migrateUsers(WikiJsSource src, MigrationReport report) {
        Set<Long> ids = new HashSet<>();
        for (WikiJsSource.User u : src.users()) {
            String role = u.groupIds().contains(ADMIN_GROUP) ? "ADMIN"
                    : u.groupIds().contains(EDITOR_GROUP) ? "EDITOR" : "VIEWER";
            jdbc.sql("""
                    INSERT INTO users (id, email, name, password_hash, role, is_active, created_at, updated_at,
                                       last_login_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(u.id(), u.email(), u.name(), u.password(), role, u.active() ? 1 : 0, u.createdAt(),
                            u.updatedAt(), u.lastLoginAt())
                    .update();
            ids.add(u.id());
        }
        report.count("사용자", ids.size());
        return ids;
    }

    /** 실제 문서 + 가상 폴더를 깊이 순으로 생성. 본문은 렌더링 후 채운다. */
    private Map<String, Long> insertTree(List<WikiJsSource.Page> srcPages, Function<Long, Long> user,
                                         MigrationReport report) {
        Map<String, WikiJsSource.Page> byPath = new HashMap<>();
        srcPages.forEach(p -> byPath.put(p.path(), p));
        Set<String> all = new TreeSet<>(byPath.keySet());
        for (String path : byPath.keySet()) {
            for (String anc = WikiPaths.parentOf(path); !anc.isEmpty(); anc = WikiPaths.parentOf(anc)) {
                all.add(anc);
            }
        }
        long nextId = srcPages.stream().mapToLong(WikiJsSource.Page::id).max().orElse(0) + 1;
        Map<String, Integer> sortOrder = siblingOrder(all, byPath);
        List<String> ordered = all.stream()
                .sorted(Comparator.comparingInt((String p) -> p.split("/").length).thenComparing(p -> p)).toList();

        Map<String, Long> idByPath = new LinkedHashMap<>();
        int virtual = 0;
        for (String path : ordered) {
            WikiJsSource.Page p = byPath.get(path);
            Long parentId = idByPath.get(WikiPaths.parentOf(path));
            String slug = WikiPaths.lastSegment(path);
            long id;
            if (p != null) {
                id = pages.insert(new PageStore.NewPage(p.id(), parentId, slug, p.title(), "", sortOrder.get(path),
                        1, user.apply(p.creatorId()), user.apply(p.authorId()), p.createdAt(), p.updatedAt()));
            } else {
                String created = srcPages.stream().filter(s -> s.path().startsWith(path + "/"))
                        .map(WikiJsSource.Page::createdAt).min(String::compareTo).orElseThrow();
                id = pages.insert(new PageStore.NewPage(nextId++, parentId, slug, slug, "", sortOrder.get(path),
                        1, null, null, created, created));
                pages.insertRevision(new PageStore.NewRevision(id, 1, slug, path, "", "created", "migration",
                        "가상 폴더에서 생성", null, null, created));
                virtual++;
            }
            if (!pages.pathOf(id).orElseThrow().equals(path)) {
                throw new IllegalStateException("path mismatch: " + path);
            }
            idByPath.put(path, id);
        }
        report.count("문서(원본)", srcPages.size());
        report.count("문서(가상 폴더)", virtual);
        return idByPath;
    }

    /** 형제 간 제목(가상 폴더는 slug) 오름차순 순번. */
    private static Map<String, Integer> siblingOrder(Set<String> all, Map<String, WikiJsSource.Page> byPath) {
        Map<String, List<String>> children = all.stream().collect(Collectors.groupingBy(WikiPaths::parentOf));
        Map<String, Integer> order = new HashMap<>();
        Function<String, String> title = p -> byPath.containsKey(p) ? byPath.get(p).title() : WikiPaths.lastSegment(p);
        children.values().forEach(list -> {
            List<String> sorted = new ArrayList<>(list);
            sorted.sort(Comparator.comparing(title, String.CASE_INSENSITIVE_ORDER));
            for (int i = 0; i < sorted.size(); i++) {
                order.put(sorted.get(i), i);
            }
        });
        return order;
    }

    private void insertOrphanPage(WikiJsSource src, List<Asset> orphans, Function<Long, Long> user,
                                  Map<Long, Set<String>> usedNames, MigrationReport report, Map<String, Long> idByPath) {
        long id = jdbc.sql("SELECT max(id) + 1 FROM pages").query(Long.class).single();
        String now = orphans.stream().map(Asset::createdAt).max(String::compareTo).orElseThrow();
        int order = (int) idByPath.keySet().stream().filter(p -> !p.contains("/")).count();
        pages.insert(new PageStore.NewPage(id, null, ORPHAN_SLUG, ORPHAN_SLUG, "", order, 1, null, null, now, now));
        StringBuilder content = new StringBuilder(
                "Wiki.js에서 어떤 문서도 참조하지 않던 첨부를 보관한 문서입니다. 필요한 문서로 옮긴 뒤 삭제하세요.\n\n");
        for (Asset a : orphans) {
            String url = attach(id, a.filename(), a.mime(), src.assetData(a.id()), user.apply(a.authorId()),
                    a.createdAt(), usedNames, report, a.id());
            content.append("- [").append(a.filename()).append("](").append(url).append(") — 원래 경로 `")
                    .append(a.urlPath()).append("`\n");
            report.item("미분류 첨부", a.urlPath());
        }
        jdbc.sql("UPDATE pages SET content = ? WHERE id = ?").params(content.toString(), id).update();
        pages.insertRevision(new PageStore.NewRevision(id, 1, ORPHAN_SLUG, ORPHAN_SLUG, content.toString(),
                "created", "migration", "미참조 에셋 보관", null, null, now));
        idByPath.put(ORPHAN_SLUG, id);
        report.count("문서(미분류 첨부)", 1);
    }

    /** 첨부 행 + 파일 기록. 같은 문서 내 파일명 충돌 시 name-1.ext. 본문 URL 반환. */
    private String attach(long pageId, String filename, String mime, byte[] data, Long createdBy, String createdAt,
                          Map<Long, Set<String>> usedNames, MigrationReport report, Long sourceAssetId) {
        Set<String> used = usedNames.computeIfAbsent(pageId, k -> new HashSet<>());
        String name = filename;
        int dot = filename.lastIndexOf('.');
        for (int n = 1; used.contains(name); n++) {
            name = dot > 0 ? filename.substring(0, dot) + "-" + n + filename.substring(dot) : filename + "-" + n;
        }
        used.add(name);
        jdbc.sql("""
                INSERT INTO attachments (page_id, filename, mime, size, sha256, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(pageId, name, mime, data.length, AttachmentStorage.sha256(data), createdBy, createdAt)
                .update();
        long id = jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
        storage.write(pageId, id, data);
        if (sourceAssetId != null) {
            report.attachmentSource.put(id, sourceAssetId);
        }
        report.add("첨부 생성", 1);
        return AttachmentStorage.url(pageId, name);
    }

    private String downloadImage(WikiJsSource.Page p, String url, Function<Long, Long> user,
                                 Map<Long, Set<String>> usedNames, MigrationReport report) {
        try {
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            String type = res.headers().firstValue("content-type").orElse("");
            if (res.statusCode() == 200 && type.toLowerCase(Locale.ROOT).startsWith("image/")) {
                String name = url.substring(url.lastIndexOf('/') + 1).split("[?#]")[0];
                String saved = attach(p.id(), name, type, res.body(), user.apply(p.authorId()), p.updatedAt(),
                        usedNames, report, null);
                report.item("외부 이미지 다운로드 성공", p.path() + ": " + url);
                return saved;
            }
            report.item("외부 이미지 다운로드 실패(원문 유지)", p.path() + ": HTTP " + res.statusCode() + " " + url);
        } catch (Exception e) {
            report.item("외부 이미지 다운로드 실패(원문 유지)", p.path() + ": " + e.getClass().getSimpleName() + " " + url);
        }
        return null;
    }

    private void migrateTags(WikiJsSource src, MigrationReport report) {
        Map<String, Long> tagIds = new HashMap<>();
        int links = 0;
        for (Map.Entry<Long, List<String>> e : src.tagsByPage().entrySet()) {
            e.getValue().stream().filter(t -> t.contains(",")).forEach(t -> report.item("콤마 포함 태그 분리", t));
            for (String name : TagNames.normalizeAll(e.getValue())) {
                Long tagId = tagIds.get(name.toLowerCase(Locale.ROOT));
                if (tagId == null) {
                    jdbc.sql("INSERT INTO tags (name) VALUES (?)").param(name).update();
                    tagId = jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
                    tagIds.put(name.toLowerCase(Locale.ROOT), tagId);
                }
                jdbc.sql("INSERT INTO page_tags (page_id, tag_id) VALUES (?, ?)").params(e.getKey(), tagId).update();
                links++;
            }
        }
        report.count("태그", tagIds.size());
        report.count("문서-태그 연결", links);
    }

    private void reportPages(List<WikiJsSource.Page> srcPages, MigrationReport report) {
        srcPages.stream().filter(p -> p.path().contains("new-page"))
                .forEach(p -> report.item("임시 문서(이전 후 UI에서 정리)", p.path()));
        jdbc.sql("""
                SELECT p.path, l.target_path FROM page_links l JOIN pages p ON p.id = l.source_page_id
                WHERE l.target_page_id IS NULL ORDER BY p.path""")
                .query((rs, n) -> {
                    report.item("레드 링크(대상 없음)", rs.getString(1) + " → " + rs.getString(2));
                    return null;
                }).list();
        report.count("위키 링크(page_links)", jdbc.sql("SELECT count(*) FROM page_links").query(Integer.class).single());
    }
}
