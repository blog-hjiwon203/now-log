package com.nhnacademy.blog.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nhnacademy.blog.IntegrationTestSupport;
import com.nhnacademy.blog.blog.domain.Blog;
import com.nhnacademy.blog.category.domain.Category;
import com.nhnacademy.blog.category.domain.CategoryRepository;
import com.nhnacademy.blog.global.host.BlogAddressResolver;
import com.nhnacademy.blog.member.domain.Member;
import com.nhnacademy.blog.post.domain.Post;
import com.nhnacademy.blog.post.domain.PostBody;
import com.nhnacademy.blog.post.domain.PostRepository;
import com.nhnacademy.blog.post.domain.Visibility;
import com.nhnacademy.blog.support.TestBlogs;
import com.nhnacademy.blog.support.TestMembers;
import com.nhnacademy.blog.support.TestPosts;
import com.nhnacademy.blog.tag.domain.Tag;
import com.nhnacademy.blog.tag.domain.TagRepository;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 블로그 RSS 피드 (T141·T142, SUB-07, contracts/rest-api.md RSS 피드).
 * 피드는 XML 파서로 읽어 확인한다(글자 비교가 아니라 리더가 읽는 것과 같은 방식으로).
 */
class RssFeedIntegrationTest extends IntegrationTestSupport {

    private static final String CONTENT_NS = "http://purl.org/rss/1.0/modules/content/";
    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestMembers testMembers;

    @Autowired
    TestBlogs testBlogs;

    @Autowired
    TestPosts testPosts;

    @Autowired
    PostRepository postRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    TagRepository tagRepository;

    @Test
    void feedHasOnlyPostsVisibleToAnonymousNewestFirst() throws Exception {
        Blog blog = testBlogs.create(testMembers.create());
        LocalDateTime base = LocalDateTime.of(2026, 11, 12, 14, 30);
        Category parent = categoryRepository.save(Category.create(blog, null, "여행", 0));
        Category child = categoryRepository.save(Category.create(blog, parent, "제주", 0));
        Category hidden = categoryRepository.save(Category.create(blog, null, "일기", 1));
        hidden.changePrivate(true);
        categoryRepository.save(hidden);

        Post older = postRepository.save(Post.published(blog, parent, "첫 글",
                new PostBody("<p>첫 본문</p>", "첫 본문", "첫 요약"), Visibility.PUBLIC, null, base));
        Post newer = post(blog, child, "둘째 글", "<p>둘째 본문</p><img src=\"/uploads/abc.png\" alt=\"사진\">",
                "둘째 요약", base.plusHours(1));
        newer.replaceTags(List.of(tagRepository.save(Tag.create(blog, "태그는빠짐"))));
        postRepository.save(newer);
        // 빠지는 글: 비공개, 구독자 공개, 임시저장, 숨김, 지움, 비공개 카테고리
        testPosts.published(blog, null, Visibility.PRIVATE, base.plusHours(2));
        testPosts.published(blog, null, Visibility.SUBSCRIBERS, base.plusHours(2));
        testPosts.draft(blog);
        testPosts.blind(testPosts.published(blog, null, Visibility.PUBLIC, base.plusHours(2)));
        testPosts.delete(testPosts.published(blog, null, Visibility.PUBLIC, base.plusHours(2)));
        testPosts.published(blog, hidden, Visibility.PUBLIC, base.plusHours(2));

        MockHttpServletResponse response = mockMvc.perform(get(feedPath(blog)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300"))
                .andReturn().getResponse();
        assertThat(MediaType.parseMediaType(response.getContentType()))
                .isEqualTo(MediaType.parseMediaType("application/rss+xml;charset=UTF-8"));

        Document xml = parse(response.getContentAsByteArray());
        Element rss = xml.getDocumentElement();
        assertThat(rss.getTagName()).isEqualTo("rss");
        assertThat(rss.getAttribute("version")).isEqualTo("2.0");

        Element channel = child(rss, "channel");
        assertThat(text(channel, "title")).isEqualTo(blog.getName());
        assertThat(text(channel, "link")).isEqualTo(TestBlogs.url(blog, "/"));
        assertThat(text(channel, "description")).isEqualTo(blog.getName());  // 소개글이 없으면 이름
        assertThat(text(channel, "lastBuildDate")).isEqualTo("Thu, 12 Nov 2026 15:30:00 +0900");
        Element self = (Element) channel.getElementsByTagNameNS(ATOM_NS, "link").item(0);
        assertThat(self.getAttribute("rel")).isEqualTo("self");
        assertThat(self.getAttribute("href")).isEqualTo(TestBlogs.url(blog, "/rss"));

        List<Element> items = children(channel, "item");
        assertThat(items).hasSize(2);
        Element first = items.get(0);
        assertThat(text(first, "title")).isEqualTo("둘째 글");
        assertThat(text(first, "link")).isEqualTo(TestBlogs.url(blog, "/" + newer.getId()));
        assertThat(text(first, "guid")).isEqualTo(TestBlogs.url(blog, "/" + newer.getId()));
        assertThat(child(first, "guid").getAttribute("isPermaLink")).isEqualTo("true");
        assertThat(text(first, "pubDate")).isEqualTo("Thu, 12 Nov 2026 15:30:00 +0900");
        // 카테고리는 하나, 하위는 "상위/하위". 태그는 넣지 않는다
        assertThat(children(first, "category")).extracting(Element::getTextContent).containsExactly("여행/제주");
        // 요약은 description, 전체 본문은 content:encoded. 본문 이미지는 전체 주소
        assertThat(text(first, "description")).isEqualTo("둘째 요약");
        String content = first.getElementsByTagNameNS(CONTENT_NS, "encoded").item(0).getTextContent();
        assertThat(content).contains("<p>둘째 본문</p>").contains("src=\"http://blog.test/uploads/abc.png\"");

        assertThat(text(items.get(1), "title")).isEqualTo(older.getTitle());
        assertThat(children(items.get(1), "category")).extracting(Element::getTextContent).containsExactly("여행");
    }

    @Test
    void ownerGetsTheSameAnonymousFeed() throws Exception {
        Member owner = testMembers.create();
        Blog blog = testBlogs.create(owner);
        testPosts.published(blog, Visibility.PUBLIC);
        testPosts.published(blog, Visibility.PRIVATE);

        Document xml = parse(mockMvc.perform(get(feedPath(blog)).cookie(testMembers.loginCookies(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());

        assertThat(xml.getElementsByTagName("item").getLength()).isEqualTo(1);
    }

    @Test
    void uncategorizedPostWithoutSummaryHasNeitherCategoryNorDescription() throws Exception {
        Blog blog = testBlogs.create(testMembers.create());
        post(blog, null, "요약 없음", "<p>본문</p>", null, LocalDateTime.now());

        Element item = (Element) parse(mockMvc.perform(get(feedPath(blog))).andReturn().getResponse()
                .getContentAsByteArray()).getElementsByTagName("item").item(0);

        assertThat(children(item, "category")).isEmpty();
        assertThat(children(item, "description")).isEmpty();
    }

    @Test
    void escapesTextAndDropsCharactersXmlCannotHold() throws Exception {
        Blog blog = testBlogs.create(testMembers.create());
        post(blog, null, "<b>A & B</b>\u0001", "<p>x &amp; y</p>", "요약 <script>", LocalDateTime.now());

        Element item = (Element) parse(mockMvc.perform(get(feedPath(blog))).andReturn().getResponse()
                .getContentAsByteArray()).getElementsByTagName("item").item(0);

        assertThat(text(item, "title")).isEqualTo("<b>A & B</b>");
        assertThat(text(item, "description")).isEqualTo("요약 <script>");
        assertThat(item.getElementsByTagNameNS(CONTENT_NS, "encoded").item(0).getTextContent())
                .isEqualTo("<p>x &amp; y</p>");
    }

    @Test
    void feedHasAtMostTwentyPosts() throws Exception {
        Blog blog = testBlogs.create(testMembers.create());
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            posts.add(testPosts.published(blog, null, Visibility.PUBLIC, base.plusMinutes(i)));
        }

        Document xml = parse(mockMvc.perform(get(feedPath(blog))).andReturn().getResponse().getContentAsByteArray());

        NodeList items = xml.getElementsByTagName("item");
        assertThat(items.getLength()).isEqualTo(20);
        // 가장 오래된 글이 빠진다
        assertThat(text((Element) items.item(19), "link")).isEqualTo(TestBlogs.url(blog, "/" + posts.get(1).getId()));
    }

    @Test
    void emptyBlogHasChannelWithoutItems() throws Exception {
        Blog blog = testBlogs.create(testMembers.create());

        Element channel = (Element) parse(mockMvc.perform(get(feedPath(blog)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).getElementsByTagName("channel").item(0);

        assertThat(children(channel, "item")).isEmpty();
        assertThat(children(channel, "lastBuildDate")).isEmpty();
    }

    @Test
    void blogsClosedToOthersAre404() throws Exception {
        mockMvc.perform(get("/@nobody-here/rss")).andExpect(status().isNotFound());
        mockMvc.perform(get("/@admin/rss")).andExpect(status().isNotFound());

        Blog deleted = testBlogs.create(testMembers.create());
        testBlogs.delete(deleted);
        mockMvc.perform(get(feedPath(deleted))).andExpect(status().isNotFound());

        Member owner = testMembers.create();
        Blog restricted = testBlogs.create(owner);
        testBlogs.restrict(restricted);
        mockMvc.perform(get(feedPath(restricted))).andExpect(status().isNotFound());
        // 피드는 비회원 기준이라 주인에게도 404다
        mockMvc.perform(get(feedPath(restricted)).cookie(testMembers.loginCookies(owner)))
                .andExpect(status().isNotFound());

        Blog suspended = testBlogs.create(testMembers.create());
        testBlogs.suspendOwner(suspended);
        mockMvc.perform(get(feedPath(suspended))).andExpect(status().isNotFound());
    }

    @Test
    void movedBlogRedirectsToTheNewFeed() throws Exception {
        Member owner = testMembers.create();
        Blog oldBlog = testBlogs.create(owner);
        Blog newBlog = testBlogs.create(owner);
        testBlogs.move(oldBlog, newBlog);
        testBlogs.delete(oldBlog);   // 옛 블로그를 지워도 이사 대상이 있으면 301

        mockMvc.perform(get(feedPath(oldBlog)))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", TestBlogs.url(newBlog, "/rss")));

        testBlogs.delete(newBlog);
        mockMvc.perform(get(feedPath(oldBlog))).andExpect(status().isNotFound());
    }

    @Test
    void blogPagesAdvertiseTheFeed() throws Exception {
        Member owner = testMembers.create();
        Blog blog = testBlogs.create(owner);
        Post post = testPosts.published(blog, Visibility.PUBLIC);
        String link = "<link rel=\"alternate\" type=\"application/rss+xml\" title=\"" + blog.getName()
                + "\" href=\"" + TestBlogs.url(blog, "/rss") + "\">";

        assertThat(page(BlogAddressResolver.blogPath(blog.getAddress(), "/"))).contains(link);
        assertThat(page(BlogAddressResolver.blogPath(blog.getAddress(), "/category/1"))).contains(link);
        // 글 화면은 공유 미리보기 태그와 함께
        assertThat(page(BlogAddressResolver.blogPath(blog.getAddress(), "/" + post.getId())))
                .contains(link).contains("og:title");
        // 관리 화면과 플랫폼 화면에는 없다
        assertThat(page(BlogAddressResolver.blogPath(blog.getAddress(), "/manage"), testMembers.loginCookies(owner)))
                .doesNotContain("application/rss+xml");
        assertThat(page("/")).doesNotContain("application/rss+xml");
    }

    @Test
    void blogClosedToOthersDoesNotAdvertiseTheFeed() throws Exception {
        Member owner = testMembers.create();
        Blog blog = testBlogs.create(owner);
        testBlogs.restrict(blog);

        // 주인은 이용 제한 블로그를 보지만, 피드는 404라 알리지 않는다
        assertThat(page(BlogAddressResolver.blogPath(blog.getAddress(), "/"), testMembers.loginCookies(owner)))
                .doesNotContain("application/rss+xml");
    }

    private Post post(Blog blog, Category category, String title, String html, String summary,
                      LocalDateTime publishedAt) {
        return postRepository.save(Post.published(blog, category, title, new PostBody(html, "본문", summary),
                Visibility.PUBLIC, null, publishedAt));
    }

    private String page(String path, Cookie... cookies) throws Exception {
        var request = get(path).accept(MediaType.TEXT_HTML);
        if (cookies.length > 0) {
            request.cookie(cookies);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String feedPath(Blog blog) {
        return BlogAddressResolver.blogPath(blog.getAddress(), "/rss");
    }

    private static Document parse(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
    }

    private static Element child(Element parent, String name) {
        List<Element> found = children(parent, name);
        assertThat(found).as(name).hasSize(1);
        return found.getFirst();
    }

    /** 바로 아래 자식 중 이름공간 없는 name 요소(channel의 title과 item의 title을 섞지 않게). */
    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && element.getNamespaceURI() == null
                    && name.equals(element.getLocalName())) {
                found.add(element);
            }
        }
        return found;
    }

    private static String text(Element parent, String name) {
        return child(parent, name).getTextContent();
    }

}
