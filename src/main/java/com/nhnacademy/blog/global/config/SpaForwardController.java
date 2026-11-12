package com.nhnacademy.blog.global.config;

import com.nhnacademy.blog.blog.domain.Blog;
import com.nhnacademy.blog.global.auth.LoginMembers;
import com.nhnacademy.blog.global.host.BlogAddressResolver;
import com.nhnacademy.blog.global.visibility.BlogVisibilityPolicy;
import com.nhnacademy.blog.global.visibility.PostAccess;
import com.nhnacademy.blog.global.visibility.PostVisibilityPolicy;
import com.nhnacademy.blog.manage.application.VisitRecorder;
import com.nhnacademy.blog.post.application.PostPreview;
import com.nhnacademy.blog.post.application.PostPreviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UrlPathHelper;

/**
 * 화면 주소 처리 (T010, 원본 6장 ①, contracts/rest-api.md 화면 주소 단계).
 * /api/ 밖의 화면 주소는 서버가 블로그와 글을 먼저 확인하고 React 앱(index.html)을 준다.
 * 없거나 볼 수 없으면 404 상태로, 이사했거나 다른 블로그 소속 글이면 서버가 직접 301로 보낸다(검색엔진도 따라간다).
 * 확장자가 있는 파일과 /api, /uploads, /assets는 여기로 오지 않는다.
 * 비회원도 볼 수 있는 글의 주소면 index.html의 head에 공유 미리보기(Open Graph) 태그를 넣는다 (T072).
 * 블로그 화면은 /@{address}/… 경로다(스텝 21, T117). 그 밖의 주소는 플랫폼 화면이다.
 * 다른 사람에게 열린 블로그 화면이면 head에 RSS 피드 자동 찾기 태그를 넣는다 (T142, SUB-07). 피드 주소 /@{address}/rss는
 * RssFeedController가 받는다.
 * 미리보기를 가져가는 서버는 자바스크립트를 실행하지 않아서, React가 그린 내용이 아니라 이 HTML만 본다.
 */
@Controller
public class SpaForwardController {

    private static final String FIRST = "{s1:(?!api$|uploads$|assets$)[^.]+}";
    /** 디코딩한 경로(%40 → @). Spring MVC가 주소를 맞출 때 쓰는 것과 같은 방식이다. */
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();
    private static final Pattern POST_PATH = Pattern.compile("^/(\\d{1,18})$");
    private static final Resource INDEX = new ClassPathResource("static/index.html");
    /** 헤더에 글자 인코딩을 밝힌다. 미리보기를 가져가는 서버는 HTML 안의 meta charset보다 헤더를 먼저 믿기도 한다. */
    private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
    private static final String NOT_BUILT = "<!doctype html><html><head><meta charset=\"utf-8\"><title>블로그</title></head>"
            + "<body><p>프론트 빌드가 없습니다. <code>./scripts/build-frontend.sh</code>를 실행하거나 "
            + "<code>cd frontend &amp;&amp; npm run dev</code>로 개발 서버를 쓰세요.</p></body></html>";

    private final BlogAddressResolver blogAddressResolver;
    private final BlogVisibilityPolicy blogVisibilityPolicy;
    private final PostVisibilityPolicy postVisibilityPolicy;
    private final PostPreviewService postPreviewService;
    private final VisitRecorder visitRecorder;

    public SpaForwardController(BlogAddressResolver blogAddressResolver, BlogVisibilityPolicy blogVisibilityPolicy,
                                PostVisibilityPolicy postVisibilityPolicy, PostPreviewService postPreviewService,
                                VisitRecorder visitRecorder) {
        this.visitRecorder = visitRecorder;
        this.blogAddressResolver = blogAddressResolver;
        this.blogVisibilityPolicy = blogVisibilityPolicy;
        this.postVisibilityPolicy = postVisibilityPolicy;
        this.postPreviewService = postPreviewService;
    }

    @GetMapping({
            "/",
            "/" + FIRST,
            "/" + FIRST + "/{s2:[^.]+}",
            "/" + FIRST + "/{s2:[^.]+}/{s3:[^.]+}",
            "/" + FIRST + "/{s2:[^.]+}/{s3:[^.]+}/{s4:[^.]+}",
            "/" + FIRST + "/{s2:[^.]+}/{s3:[^.]+}/{s4:[^.]+}/{s5:[^.]+}",
            // 경로 변수(s1~s5, name)는 칸 모양(점 없음 = 정적 파일이 아님)을 정하려고 둔 것이고, 값은 아래에서 경로 전체로 읽는다
            // 태그 이름에는 점이 들어갈 수 있다(node.js). 확장자로 보지 않는다 (TAG-02)
            "/{s1:@[^.]+}/tag/{name:.+}"})
    public ResponseEntity<Resource> page(HttpServletRequest request, HttpServletResponse response) {
        String path = PATH_HELPER.getPathWithinApplication(request);
        if (!path.startsWith(BlogAddressResolver.PATH_PREFIX)) {
            return app(HttpStatus.OK);
        }
        // /@alpha/12 → 주소 alpha, 블로그 안 경로 /12. 블로그 안 경로가 없으면 첫 화면(/)
        String rest = path.substring(BlogAddressResolver.PATH_PREFIX.length());
        int slash = rest.indexOf('/');
        String address = slash < 0 ? rest : rest.substring(0, slash);
        String blogPath = slash < 0 ? "/" : rest.substring(slash);
        return blogPage(request, response, address, blogPath);
    }

    /** 규칙에 맞지 않거나 예약어인 주소, 없는 블로그는 404다. */
    private ResponseEntity<Resource> blogPage(HttpServletRequest request, HttpServletResponse response,
                                              String address, String path) {
        Optional<Blog> found = blogAddressResolver.findBlog(address);
        if (found.isEmpty()) {
            return app(HttpStatus.NOT_FOUND);
        }
        Blog blog = found.get();
        Long viewerId = LoginMembers.currentId();

        // 글 주소는 글이 어느 블로그에 있는지로 판단한다. 이사 전 블로그에 남은 글은 옛 주소에서 그대로 보인다.
        // 옛 블로그가 지워졌어도 옮긴 글은 새 블로그로 301, 옮기지 않은 글은 함께 지워져 404다(BLOG-07)
        var postPath = POST_PATH.matcher(path);
        if (postPath.matches()) {
            Long postId = Long.valueOf(postPath.group(1));
            return switch (postVisibilityPolicy.decide(postId, blog, viewerId)) {
                case PostAccess.MovedTo movedTo -> redirect(request, movedTo.blog(), path);
                case PostAccess.NotFound notFound -> app(HttpStatus.NOT_FOUND);
                // 구독 안내는 화면이 API의 403 SUBSCRIBERS_ONLY로 그린다
                case PostAccess.Owner owner -> postPage(request, blog, postId);
                case PostAccess.Visible visible -> visited(blog, request, response, postPage(request, blog, postId));
                case PostAccess.SubscribersOnly subscribersOnly ->
                        visited(blog, request, response, blogApp(request, blog));
            };
        }

        // 이사한 블로그는 지워졌어도 새 블로그로 보낸다(spec 블로그 주소·이사·삭제 표 "삭제 여부 무관")
        if (blog.isDeleted() && !blog.isMoved()) {
            return app(HttpStatus.NOT_FOUND);
        }
        if (!blog.isDeleted() && !blogVisibilityPolicy.canView(blog, viewerId)) {
            return app(HttpStatus.NOT_FOUND);
        }
        if (blog.isMoved()) {
            // 주인은 옛 블로그 관리 화면을 계속 쓴다 (BLOG-06). 지운 블로그의 관리 화면은 없다
            if (isManagePath(path) && blog.isOwnedBy(viewerId) && !blog.isDeleted()) {
                return app(HttpStatus.OK);
            }
            Blog target = blog.getMovedToBlog();
            // 이사 간 블로그가 삭제되면 리다이렉트도 끊기고 없는 블로그다 (R-08)
            return target.isDeleted() ? app(HttpStatus.NOT_FOUND) : redirect(request, target, path);
        }
        return isManagePath(path) ? app(HttpStatus.OK) : visited(blog, request, response, blogApp(request, blog));
    }

    /** 블로그 화면(관리 화면 말고). 다른 사람에게 열린 블로그면 피드 자동 찾기 태그를 넣은 index.html. */
    private ResponseEntity<Resource> blogApp(HttpServletRequest request, Blog blog) {
        return blogVisibilityPolicy.isOpenToOthers(blog)
                ? html(HttpStatus.OK, withFeedLink(indexHtml(), request, blog))
                : app(HttpStatus.OK);
    }

    /**
     * head 끝에 RSS 자동 찾기 태그를 넣는다(HTML 표준 rel=alternate, RSS Autodiscovery). 링크만 받은 리더가 여기서 피드를 찾는다.
     * 피드는 비회원 기준이라, 이용 제한·주인 정지로 다른 사람에게 닫힌 블로그(피드가 404)에는 넣지 않는다.
     * title은 사용자가 쓴 블로그 이름이라 HTML로 이스케이프한다.
     */
    private String withFeedLink(String html, HttpServletRequest request, Blog blog) {
        if (!blogVisibilityPolicy.isOpenToOthers(blog)) {
            return html;
        }
        String tag = "<link rel=\"alternate\" type=\"application/rss+xml\" title=\""
                + HtmlUtils.htmlEscape(blog.getName()) + "\" href=\""
                + HtmlUtils.htmlEscape(blogAddressResolver.blogUrl(request, blog, "/rss")) + "\">\n";
        return html.replace("</head>", tag + "</head>");
    }

    /**
     * 블로그 화면을 정상으로 보여 줄 때 방문을 남긴다 (T082, MNG-03). 주인 본인은 VisitRecorder가 세지 않는다.
     * 404·301과 관리 화면은 방문이 아니라서 남기지 않는다.
     */
    private ResponseEntity<Resource> visited(Blog blog, HttpServletRequest request, HttpServletResponse response,
                                             ResponseEntity<Resource> page) {
        visitRecorder.record(blog, request, response);
        return page;
    }

    /** 글 화면. 비회원도 볼 수 있는 글이면 공유 미리보기 태그를 넣은 index.html, 아니면 그대로(자동 찾기 태그는 둘 다). */
    private ResponseEntity<Resource> postPage(HttpServletRequest request, Blog blog, Long postId) {
        return postPreviewService.preview(blog, postId)
                .map(preview -> html(HttpStatus.OK,
                        withFeedLink(withPreview(indexHtml(), preview, request, blog), request, blog)))
                .orElseGet(() -> blogApp(request, blog));
    }

    /**
     * head 끝에 Open Graph 태그를 넣는다. 값은 사용자가 쓴 글자(제목·요약·블로그 이름)라 HTML로 이스케이프한다
     * (제목에 "><script>를 넣어 태그를 깨는 공격 막기, XSS). og:url·og:image는 절대 주소여야 한다.
     */
    private String withPreview(String html, PostPreview preview, HttpServletRequest request, Blog blog) {
        String title = preview.title() + " - " + preview.blogName();
        StringBuilder tags = new StringBuilder()
                .append(meta("og:type", "article"))
                .append(meta("og:site_name", preview.blogName()))
                .append(meta("og:title", preview.title()))
                .append(meta("og:url", blogAddressResolver.blogUrl(request, blog, "/" + preview.postId())));
        if (preview.description() != null && !preview.description().isBlank()) {
            tags.append(meta("og:description", preview.description()))
                    .append(namedMeta("description", preview.description()));
        }
        if (preview.imagePath() != null) {
            // 이미지는 블로그 경로가 아니라 /uploads/…에서 나간다(스텝 21)
            tags.append(meta("og:image", blogAddressResolver.platformUrl(request, preview.imagePath())));
        }
        tags.append(namedMeta("twitter:card", preview.imagePath() != null ? "summary_large_image" : "summary"));
        String titleTag = "<title>" + HtmlUtils.htmlEscape(title) + "</title>";
        return html.replaceFirst("<title>[^<]*</title>", Matcher.quoteReplacement(titleTag))
                .replace("</head>", tags + "</head>");
    }

    private static String meta(String property, String content) {
        return metaTag("property", property, content);
    }

    private static String namedMeta(String name, String content) {
        return metaTag("name", name, content);
    }

    private static String metaTag(String attribute, String key, String content) {
        return "<meta " + attribute + "=\"" + key + "\" content=\"" + HtmlUtils.htmlEscape(content) + "\">\n";
    }

    private String indexHtml() {
        if (!INDEX.exists()) {
            return NOT_BUILT;
        }
        try (var input = INDEX.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ResponseEntity<Resource> html(HttpStatus status, String html) {
        return ResponseEntity.status(status)
                .contentType(HTML_UTF8)
                .cacheControl(CacheControl.noCache())
                .body(new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8)));
    }

    /** 같은 블로그 안 경로(path)를 새 블로그 주소로 301. */
    private ResponseEntity<Resource> redirect(HttpServletRequest request, Blog target, String path) {
        String query = request.getQueryString();
        String pathAndQuery = path + (query == null ? "" : "?" + query);
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create(blogAddressResolver.blogUrl(request, target, pathAndQuery)))
                .build();
    }

    private ResponseEntity<Resource> app(HttpStatus status) {
        return ResponseEntity.status(status)
                .contentType(HTML_UTF8)
                .cacheControl(CacheControl.noCache())
                .body(INDEX.exists() ? INDEX : notBuilt());
    }

    /** 프론트를 빌드하지 않았을 때(개발 중 백엔드만 띄움) 보여 줄 안내. */
    private Resource notBuilt() {
        return new ByteArrayResource(NOT_BUILT.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isManagePath(String path) {
        return path.equals("/manage") || path.startsWith("/manage/");
    }

}
