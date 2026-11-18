package io.github.yuchoe.pagebound.server.preview;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab URL 해석 (spec/features/F10-link-preview.md 3절). `/-/` 없는 구형 형식과 하위 그룹을 지원한다.
 *
 * @param ref MR·이슈 iid, 커밋 sha, 위키 slug, 파일 ref
 */
public record GitLabUrl(LinkPreviewProperties.GitLab host, String project, String kind, String ref, String filePath,
                        Integer lineStart, Integer lineEnd, Long noteId) {

    private static final Set<String> ACTIONS = Set.of("merge_requests", "issues", "commit", "blob", "wikis");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{7,40}");
    private static final Pattern NOTE = Pattern.compile("note_(\\d+)");
    private static final Pattern LINES = Pattern.compile("L(\\d+)(?:-L?(\\d+))?");

    public static Optional<GitLabUrl> parse(String url, List<LinkPreviewProperties.GitLab> hosts) {
        for (LinkPreviewProperties.GitLab host : hosts) {
            String base = host.base();
            if (url.startsWith(base + "/")) {
                return parse(host, url.substring(base.length()));
            }
        }
        return Optional.empty();
    }

    private static Optional<GitLabUrl> parse(LinkPreviewProperties.GitLab host, String rest) {
        String fragment = null;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        int query = rest.indexOf('?');
        if (query >= 0) {
            rest = rest.substring(0, query);
        }
        List<String> segs = Arrays.stream(rest.split("/")).filter(s -> !s.isEmpty()).toList();
        int dash = segs.indexOf("-");
        int actionAt;
        List<String> project;
        if (dash >= 2) {
            project = segs.subList(0, dash);
            actionAt = dash + 1;
        } else {
            actionAt = -1;
            for (int i = 2; i < segs.size(); i++) {
                if (ACTIONS.contains(segs.get(i))) {
                    actionAt = i;
                    break;
                }
            }
            project = actionAt < 0 ? segs : segs.subList(0, actionAt);
        }
        if (project.size() < 2) {
            return Optional.empty();
        }
        String projectPath = String.join("/", project);
        if (actionAt < 0) {
            return Optional.of(new GitLabUrl(host, projectPath, "project", null, null, null, null, null));
        }
        if (actionAt >= segs.size()) {
            return Optional.empty();
        }
        String action = segs.get(actionAt);
        List<String> args = segs.subList(actionAt + 1, segs.size());
        if (args.isEmpty()) {
            return Optional.empty();
        }
        Long note = null;
        Integer start = null;
        Integer end = null;
        if (fragment != null) {
            Matcher n = NOTE.matcher(fragment);
            Matcher l = LINES.matcher(fragment);
            if (n.matches()) {
                note = Long.parseLong(n.group(1));
            } else if (l.matches()) {
                start = Integer.parseInt(l.group(1));
                end = l.group(2) == null ? start : Integer.parseInt(l.group(2));
            }
        }
        String first = args.getFirst();
        return switch (action) {
            case "merge_requests" -> NUMBER.matcher(first).matches()
                    ? Optional.of(new GitLabUrl(host, projectPath, "merge_request", first, null, null, null, note))
                    : Optional.empty();
            case "issues" -> NUMBER.matcher(first).matches()
                    ? Optional.of(new GitLabUrl(host, projectPath, "issue", first, null, null, null, note))
                    : Optional.empty();
            case "commit" -> SHA.matcher(first).matches()
                    ? Optional.of(new GitLabUrl(host, projectPath, "commit", first, null, null, null, note))
                    : Optional.empty();
            case "wikis" -> Optional.of(new GitLabUrl(host, projectPath, "wiki", String.join("/", args), null,
                    null, null, null));
            case "blob" -> args.size() < 2 ? Optional.empty()
                    : Optional.of(new GitLabUrl(host, projectPath, "blob", first,
                            String.join("/", args.subList(1, args.size())), start, end, null));
            default -> Optional.empty();
        };
    }

    /** 캐시 키: 같은 대상의 여러 URL 형태(/diffs, 구형 형식 등)를 하나로 모은다. */
    public String canonical() {
        String base = host.base() + "/" + project;
        String url = switch (kind) {
            case "merge_request" -> base + "/-/merge_requests/" + ref;
            case "issue" -> base + "/-/issues/" + ref;
            case "commit" -> base + "/-/commit/" + ref;
            case "wiki" -> base + "/-/wikis/" + ref;
            case "blob" -> base + "/-/blob/" + ref + "/" + filePath
                    + (lineStart == null ? "" : "#L" + lineStart + (lineEnd.equals(lineStart) ? "" : "-" + lineEnd));
            default -> base;
        };
        return noteId == null ? url : url + "#note_" + noteId;
    }

    /** 칩 표시용: project!13, project#5, project@abc1234 … (프로젝트 이름은 마지막 경로). */
    public String label() {
        String name = project.substring(project.lastIndexOf('/') + 1);
        return switch (kind) {
            case "merge_request" -> name + "!" + ref;
            case "issue" -> name + "#" + ref;
            case "commit" -> name + "@" + ref.substring(0, Math.min(8, ref.length()));
            case "wiki" -> name + " 위키: " + ref;
            case "blob" -> name + ": " + filePath;
            default -> project;
        };
    }
}
