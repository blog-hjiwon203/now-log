package com.nhnacademy.blog.rss.application;

import com.nhnacademy.blog.blog.domain.Blog;
import com.nhnacademy.blog.category.domain.Category;
import com.nhnacademy.blog.global.host.BlogAddressResolver;
import com.nhnacademy.blog.global.visibility.BlogVisibilityPolicy;
import com.nhnacademy.blog.global.visibility.PostSpecifications;
import com.nhnacademy.blog.post.domain.Post;
import com.nhnacademy.blog.post.domain.PostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 RSS 피드 (T141, SUB-07, research R-19).
 * 피드를 읽는 프로그램(RSS 리더, 다른 앱)은 로그인하지 않으므로 늘 비회원 기준으로 만든다.
 * 주인이 로그인한 채 열어도 비공개 글이 섞이지 않는다(그 피드 주소가 그대로 퍼질 수 있어서).
 */
@Service
public class RssFeedService {

    /** 피드에 넣는 글 수. 구독 피드(SUB-02)의 한 번 분량과 같다. */
    public static final int SIZE = 20;

    private static final Sort LATEST = Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"));

    private final BlogAddressResolver blogAddressResolver;
    private final BlogVisibilityPolicy blogVisibilityPolicy;
    private final PostRepository postRepository;
    private final Clock clock;

    public RssFeedService(BlogAddressResolver blogAddressResolver, BlogVisibilityPolicy blogVisibilityPolicy,
                          PostRepository postRepository, Clock clock) {
        this.blogAddressResolver = blogAddressResolver;
        this.blogVisibilityPolicy = blogVisibilityPolicy;
        this.postRepository = postRepository;
        this.clock = clock;
    }

    /**
     * 블로그 화면 주소와 같은 순서로 판단한다(SpaForwardController): 이사한 블로그는 지워졌어도 새 블로그로,
     * 지운 블로그와 다른 사람에게 닫힌 블로그(이용 제한, 주인 정지)는 404.
     */
    @Transactional(readOnly = true)
    public RssFeedLookup find(String address) {
        Optional<Blog> found = blogAddressResolver.findBlog(address);
        if (found.isEmpty()) {
            return new RssFeedLookup.NotFound();
        }
        Blog blog = found.get();
        if (blog.isMoved()) {
            Blog target = blog.getMovedToBlog();
            return target.isDeleted() ? new RssFeedLookup.NotFound() : new RssFeedLookup.MovedTo(target.getAddress());
        }
        if (!blogVisibilityPolicy.isOpenToOthers(blog)) {
            return new RssFeedLookup.NotFound();
        }
        return new RssFeedLookup.Found(new RssFeed(blog.getAddress(), blog.getName(), blog.getDescription(),
                items(blog)));
    }

    /** 비회원이 볼 수 있는 글(전체 공개·발행됨·숨김 아님·비공개 카테고리 아님) 최신 SIZE개. */
    private List<RssFeed.Item> items(Blog blog) {
        Specification<Post> condition = PostSpecifications.inBlog(blog.getId())
                .and(PostSpecifications.visibleTo(null, LocalDateTime.now(clock)));
        // 카테고리를 함께 읽는다. 상위 카테고리는 이 트랜잭션 안에서 읽힌다(블로그 하나의 카테고리라 몇 개 안 된다)
        List<Post> posts = postRepository.findBy(condition,
                query -> query.sortBy(LATEST).project("category").limit(SIZE).all());
        return posts.stream()
                .map(post -> new RssFeed.Item(post.getId(), post.getTitle(), post.getSummary(), post.getContentHtml(),
                        categoryPath(post.getCategory()), post.getPublishedAt()))
                .toList();
    }

    /** RSS 2.0의 category 값은 "/"로 나눈 계층 경로로 쓸 수 있다. 카테고리는 두 단계까지다(CAT-03). */
    static String categoryPath(Category category) {
        if (category == null) {
            return null;
        }
        return category.isChild() ? category.getParent().getName() + "/" + category.getName() : category.getName();
    }

}
