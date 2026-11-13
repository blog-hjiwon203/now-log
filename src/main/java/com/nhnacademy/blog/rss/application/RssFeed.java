package com.nhnacademy.blog.rss.application;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 블로그 하나의 RSS 피드 내용 (SUB-07). XML 모양과 전체 주소는 화면 쪽(RssFeedWriter)이 만든다.
 *
 * @param address     블로그 주소(alpha)
 * @param name        블로그 이름 → channel/title
 * @param description 블로그 소개글. 비었을 수 있다 → channel/description
 * @param items       비회원이 볼 수 있는 글, 최신순
 */
public record RssFeed(String address, String name, String description, List<Item> items) {

    /**
     * 글 하나.
     *
     * @param postId       글 번호. 글 주소(link·guid)를 만든다
     * @param title        제목
     * @param summary      목록용 요약(글자만, 300자). 비었을 수 있다 → description
     * @param contentHtml  정화를 거친 전체 본문 HTML → content:encoded
     * @param category     카테고리 경로("상위/하위" 또는 "이름"). 미분류면 null
     * @param publishedAt  발행 시각(한국 시간) → pubDate
     */
    public record Item(Long postId, String title, String summary, String contentHtml, String category,
                       LocalDateTime publishedAt) {
    }

}
