package com.nhnacademy.blog.rss.application;

import com.nhnacademy.blog.blog.domain.Blog;

/** 블로그 주소로 피드를 찾은 결과. 블로그 화면 주소와 같은 규칙으로 404·301을 가른다 (SUB-07, BLOG-06·07). */
public sealed interface RssFeedLookup {

    /** 없거나 다른 사람이 볼 수 없는 블로그. 피드는 비회원 기준이라 주인에게도 404다. */
    record NotFound() implements RssFeedLookup {
    }

    /** 이사한 블로그. 새 블로그의 피드로 301. */
    record MovedTo(String address) implements RssFeedLookup {
    }

    record Found(RssFeed feed) implements RssFeedLookup {
    }

}
