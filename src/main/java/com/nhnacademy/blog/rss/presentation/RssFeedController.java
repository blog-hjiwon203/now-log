package com.nhnacademy.blog.rss.presentation;

import com.nhnacademy.blog.global.host.BlogAddressResolver;
import com.nhnacademy.blog.rss.application.RssFeed;
import com.nhnacademy.blog.rss.application.RssFeedLookup;
import com.nhnacademy.blog.rss.application.RssFeedService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블로그 RSS 피드 주소 {@code /@{address}/rss} (T141, SUB-07).
 * 화면 주소를 받는 SpaForwardController의 {@code /{s1}/{s2}}보다 글자가 고정된 이 주소가 더 구체적이라 여기로 온다.
 * JSON API가 아니라서 /api 밖에 두고, 오류도 JSON 본문 없이 상태 코드만 준다(리더는 상태 코드만 본다).
 */
@RestController
public class RssFeedController {

    static final String RSS_TYPE = "application/rss+xml";
    private static final String RSS_UTF8 = RSS_TYPE + ";charset=UTF-8";
    /** 리더가 같은 피드를 자주 읽으므로 5분 동안은 다시 묻지 않아도 된다고 알린다. */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5));
    private static final String UPLOADS_SRC = "src=\"/uploads/";

    private final RssFeedService rssFeedService;
    private final BlogAddressResolver blogAddressResolver;

    public RssFeedController(RssFeedService rssFeedService, BlogAddressResolver blogAddressResolver) {
        this.rssFeedService = rssFeedService;
        this.blogAddressResolver = blogAddressResolver;
    }

    @GetMapping("/@{address}/rss")
    public ResponseEntity<byte[]> feed(@PathVariable String address, HttpServletRequest request) {
        return switch (rssFeedService.find(address)) {
            case RssFeedLookup.NotFound notFound -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case RssFeedLookup.MovedTo movedTo -> ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                    .location(URI.create(feedUrl(request, movedTo.address())))
                    .build();
            case RssFeedLookup.Found found -> ResponseEntity.ok()
                    .header("Content-Type", RSS_UTF8)
                    .cacheControl(CACHE)
                    .body(RssFeedWriter.write(found.feed(), urls(request, found.feed())));
        };
    }

    /** 피드 전체 주소. 블로그 화면의 자동 찾기 태그(SpaForwardController)도 같은 /@{address}/rss를 가리킨다. */
    private String feedUrl(HttpServletRequest request, String address) {
        return blogAddressResolver.platformUrl(request, BlogAddressResolver.blogPath(address, "/rss"));
    }

    private RssFeedWriter.Urls urls(HttpServletRequest request, RssFeed feed) {
        String address = feed.address();
        // 본문 이미지는 /uploads/… 상대 주소다(HtmlSanitizer가 이 모양만 남긴다). 피드 밖에서도 열리게 전체 주소로 바꾼다
        String uploads = "src=\"" + blogAddressResolver.platformUrl(request, "/uploads/");
        return new RssFeedWriter.Urls(
                blogAddressResolver.platformUrl(request, BlogAddressResolver.blogPath(address, "/")),
                feedUrl(request, address),
                postId -> blogAddressResolver.platformUrl(request, BlogAddressResolver.blogPath(address, "/" + postId)),
                html -> html == null ? "" : html.replace(UPLOADS_SRC, uploads));
    }

}
