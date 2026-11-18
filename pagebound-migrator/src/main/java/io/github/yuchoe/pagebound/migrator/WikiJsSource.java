package io.github.yuchoe.pagebound.migrator;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.sqlite.SQLiteConfig;

/**
 * Wiki.js 2.x export(database.sqlite) 읽기 전용 접근.
 * 사용 컬럼은 원본 스키마 확인 결과(spec/00-analysis.md)만 사용한다.
 */
final class WikiJsSource implements AutoCloseable {

    record User(long id, String email, String name, String password, boolean active, String createdAt,
                String updatedAt, String lastLoginAt, Set<Long> groupIds) {
    }

    record Page(long id, String path, String title, String content, long authorId, long creatorId,
                String createdAt, String updatedAt) {
    }

    record History(long id, long pageId, String path, String title, String content, String action,
                   long authorId, String versionDate) {
    }

    record Asset(long id, String filename, String folderPath, String mime, String createdAt, Long authorId) {
        /** 본문에서 참조하는 절대 경로: /{folder}/{filename} */
        String urlPath() {
            return "/" + (folderPath.isEmpty() ? "" : folderPath + "/") + filename;
        }
    }

    private final Connection connection;
    private final JdbcClient jdbc;

    WikiJsSource(Path file) {
        try {
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            this.connection = config.createConnection("jdbc:sqlite:" + file);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open source " + file, e);
        }
        this.jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    /** 시스템 계정과 비밀번호 없는 계정 제외. */
    List<User> users() {
        Map<Long, Set<Long>> groups = new HashMap<>();
        jdbc.sql("SELECT userId, groupId FROM userGroups").query((rs, n) -> {
            groups.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getLong(2));
            return null;
        }).list();
        return jdbc.sql("""
                SELECT id, email, name, password, isActive, createdAt, updatedAt, lastLoginAt
                FROM users WHERE isSystem = 0 AND password IS NOT NULL AND password <> '' ORDER BY id""")
                .query((rs, n) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        groups.getOrDefault(rs.getLong(1), Set.of())))
                .list();
    }

    List<Page> pages() {
        return jdbc.sql("""
                SELECT id, path, title, content, authorId, creatorId, createdAt, updatedAt
                FROM pages ORDER BY id""")
                .query((rs, n) -> new Page(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4) == null ? "" : rs.getString(4), rs.getLong(5), rs.getLong(6),
                        rs.getString(7), rs.getString(8)))
                .list();
    }

    /** 현재 존재하는 문서의 이력만, 문서별 versionDate 오름차순. */
    List<History> history() {
        return jdbc.sql("""
                SELECT id, pageId, path, title, content, action, authorId, versionDate
                FROM pageHistory WHERE pageId IN (SELECT id FROM pages)
                ORDER BY pageId, versionDate, id""")
                .query((rs, n) -> new History(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5) == null ? "" : rs.getString(5), rs.getString(6), rs.getLong(7),
                        rs.getString(8)))
                .list();
    }

    int historyOfDeletedPages() {
        return jdbc.sql("SELECT count(*) FROM pageHistory WHERE pageId NOT IN (SELECT id FROM pages)")
                .query(Integer.class).single();
    }

    List<Asset> assets() {
        record Folder(String slug, Long parentId) {
        }
        Map<Long, Folder> folders = new HashMap<>();
        jdbc.sql("SELECT id, slug, parentId FROM assetFolders").query((rs, n) -> {
            long parent = rs.getLong(3);
            Long parentId = rs.wasNull() ? null : parent;  // wasNull은 직전에 읽은 컬럼 기준
            folders.put(rs.getLong(1), new Folder(rs.getString(2), parentId));
            return null;
        }).list();
        return jdbc.sql("SELECT id, filename, folderId, mime, createdAt, authorId FROM assets ORDER BY id")
                .query((rs, n) -> {
                    long folderId = rs.getLong(3);
                    Long fid = rs.wasNull() ? null : folderId;
                    List<String> segments = new ArrayList<>();
                    while (fid != null) {
                        Folder f = folders.get(fid);
                        segments.addFirst(f.slug());
                        fid = f.parentId();
                    }
                    long author = rs.getLong(6);
                    Long authorId = rs.wasNull() ? null : author;
                    return new Asset(rs.getLong(1), rs.getString(2), String.join("/", segments), rs.getString(4),
                            rs.getString(5), authorId);
                })
                .list();
    }

    byte[] assetData(long assetId) {
        return jdbc.sql("SELECT data FROM assetData WHERE id = ?").param(assetId)
                .query((rs, n) -> rs.getBytes(1)).single();
    }

    /** 문서별 원본 태그 문자열 (정규화 전). */
    Map<Long, List<String>> tagsByPage() {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT pt.pageId, t.tag FROM pageTags pt JOIN tags t ON t.id = pt.tagId
                WHERE pt.pageId IN (SELECT id FROM pages) ORDER BY pt.pageId, pt.id""")
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                    return null;
                }).list();
        return out;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
