package com.nhnacademy.blog.post.domain;

import com.nhnacademy.blog.blog.domain.Blog;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long>, JpaSpecificationExecutor<Post>,
        PostCountRepository {

    /**
     * 가시성 판단에 필요한 블로그와 블로그 주인, 카테고리와 그 상위(비공개 카테고리 판단)를 함께 읽는다. 삭제된 글도 나온다.
     * 화면 주소 처리처럼 트랜잭션 밖에서도 쓰므로 판단에 드는 것은 모두 여기서 읽는다.
     */
    @Query("select p from Post p join fetch p.blog b join fetch b.member left join fetch p.category c left join fetch c.parent where p.id = :id")
    Optional<Post> findWithBlogById(@Param("id") Long id);

    /**
     * 관리자 숨김·해제 (ADMIN-03). 작성자가 고친 것이 아니라 수정 시각은 그대로 둔다(글 머리의 "수정 …"이 바뀌지 않게).
     * updated_at은 엔티티를 고칠 때 JPA(@LastModifiedDate)가 채우는 값이라, 이런 벌크 UPDATE에서는 바뀌지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Post p set p.blinded = :blinded where p.id = :id")
    int changeBlinded(@Param("id") Long id, @Param("blinded") boolean blinded);

    /**
     * 글을 다른 블로그로 옮긴다 (BLOG-06). 카테고리는 미분류. 작성자가 글을 고친 것이 아니라서 수정 시각은 그대로 둔다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Post p set p.blog = :target, p.category = null where p.id in :ids")
    int moveTo(@Param("ids") Collection<Long> ids, @Param("target") Blog target);

    /** 블로그의 지우지 않은 글 번호(임시저장·예약 포함). 블로그 삭제·탈퇴에서 함께 지울 글. */
    @Query("select p.id from Post p where p.blog.id = :blogId and p.deletedAt is null")
    List<Long> findLiveIdsByBlogId(@Param("blogId") Long blogId);

    /** 이 시각 뒤에 발행된 글 수(지운 글 빼고, 관리자 대시보드). */
    @Query("select count(p) from Post p where p.status = com.nhnacademy.blog.post.domain.PostStatus.PUBLISHED"
            + " and p.publishedAt >= :since and p.deletedAt is null")
    long countPublishedSince(@Param("since") LocalDateTime since);

    /**
     * 글 목록(페이지). 목록 한 줄에 카테고리 이름이 나가므로 카테고리를 함께 읽는다(N+1 방지).
     * 개수 쿼리에는 이 fetch가 붙지 않는다.
     */
    @Override
    @EntityGraph(attributePaths = "category")
    Page<Post> findAll(Specification<Post> spec, Pageable pageable);

    /**
     * 카테고리를 지울 때 그 카테고리의 글을 미분류로 옮긴다 (CAT-01). 삭제된 글도 함께 옮겨 외래 키가 남지 않게 한다.
     * 작성자가 고친 것이 아니라서 수정 시각을 남기지 않는다(벌크 UPDATE라 updated_at이 바뀌지 않는다).
     */
    @Modifying(clearAutomatically = true)
    @Query("update Post p set p.category = null where p.category.id = :categoryId")
    int uncategorize(@Param("categoryId") Long categoryId);

    /** 글을 지우면 그 글의 공감도 지운다 (POST-03). 공감 엔티티는 스텝 7에서 만들므로 SQL로 지운다. */
    @Modifying
    @Query(value = "DELETE FROM post_like WHERE post_id = :postId", nativeQuery = true)
    int deleteLikes(@Param("postId") Long postId);

    /** 글과 그 글의 댓글을 가리키는 알림을 지운다 (POST-03). 알림 기능(SUB-04)보다 먼저 삭제 규칙을 맞춰 둔다. */
    @Modifying
    @Query(value = "DELETE FROM notification WHERE (target_type = 'POST' AND target_id = :postId)"
            + " OR (target_type = 'COMMENT' AND target_id IN (SELECT id FROM comment WHERE post_id = :postId))",
            nativeQuery = true)
    int deleteNotifications(@Param("postId") Long postId);

    /**
     * 댓글 수 늘리기·줄이기 (CMT-01). 한 줄 UPDATE라 동시에 여러 댓글이 달려도 값이 어긋나지 않는다.
     * 작성자가 글을 고친 것이 아니라서 updated_at은 그대로 둔다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Post p set p.commentCount = p.commentCount + :delta where p.id = :postId")
    int addCommentCount(@Param("postId") Long postId, @Param("delta") int delta);

    /** 조회수 하나 올리기 (POST-09). 댓글 수와 같은 한 줄 UPDATE이고, 조회는 글을 고친 것이 아니라 updated_at을 그대로 둔다. */
    @Modifying(clearAutomatically = true)
    @Query("update Post p set p.viewCount = p.viewCount + 1 where p.id = :postId")
    int increaseViewCount(@Param("postId") Long postId);

    /**
     * 블로그별 지우지 않은 발행 글 수 [블로그 id, 글 수] (마이페이지 내 블로그, BLOG-08).
     * 주인이 자기 블로그 화면에서 보는 글 수와 같은 조건이다(비공개·숨김 포함, 임시저장 제외).
     */
    @Query("select p.blog.id, count(p) from Post p where p.blog.id in :blogIds"
            + " and p.status = com.nhnacademy.blog.post.domain.PostStatus.PUBLISHED and p.deletedAt is null"
            + " group by p.blog.id")
    List<Object[]> countPublishedByBlogIds(@Param("blogIds") Collection<Long> blogIds);

    /** 지우지 않은 발행 글 번호 전부. 추천 임베딩을 서버가 뜰 때 채우는 데 쓴다 (T069b). */
    @Query("select p.id from Post p where p.status = com.nhnacademy.blog.post.domain.PostStatus.PUBLISHED"
            + " and p.deletedAt is null")
    List<Long> findPublishedIds();

    /** 예약 시각이 지금이거나 지난 예약 글 번호 (POST-13). 지운 글은 뺀다. 발행은 글마다 publishIfDue로 한다. */
    @Query("select p.id from Post p where p.status = com.nhnacademy.blog.post.domain.PostStatus.SCHEDULED"
            + " and p.deletedAt is null and p.scheduledAt <= :now")
    List<Long> findDueScheduledIds(@Param("now") LocalDateTime now);

    /**
     * 예약 글을 발행한다 (POST-13). 예약 시각이 처음 발행 시각이고(작업이 늦게 돌아도 목록 순서는 정한 시각대로), 공개 범위는 공개.
     * 읽은 엔티티를 저장하지 않고 조건을 단 UPDATE 한 문장으로 바꾼다. 바뀐 행 수(0 또는 1)를 돌려준다.
     * <ul>
     *   <li>앱 여러 대가 같은 글을 동시에 발행하려 해도 1을 받는 쪽은 하나다. 먼저 UPDATE한 쪽이 행을 잠그고, 뒤쪽은 기다렸다가
     *       커밋된 행으로 WHERE를 다시 보는데(PostgreSQL READ COMMITTED) 이미 PUBLISHED라 0이 된다.</li>
     *   <li>번호를 읽은 뒤 글쓴이가 예약을 거두거나 시각을 미루거나 글을 지웠으면 조건에 맞지 않아 0이다.</li>
     *   <li>발행에 필요한 칸만 바꾸므로, 그사이 글쓴이가 고친 제목·본문을 덮어쓰지 않는다.</li>
     * </ul>
     * 작성자가 고친 것이 아니라서 updated_at은 그대로 둔다(글 머리에 "수정 …"이 붙지 않게).
     * SET의 오른쪽은 바꾸기 전 행 값을 보므로 publishedAt에는 지우기 전의 scheduledAt이 들어간다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Post p set p.status = com.nhnacademy.blog.post.domain.PostStatus.PUBLISHED,"
            + " p.publishedAt = p.scheduledAt, p.visibility = com.nhnacademy.blog.post.domain.Visibility.PUBLIC,"
            + " p.scheduledAt = null"
            + " where p.id = :id and p.status = com.nhnacademy.blog.post.domain.PostStatus.SCHEDULED"
            + " and p.deletedAt is null and p.scheduledAt <= :now")
    int publishIfDue(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 글에 단 태그 이름, 가나다순. 추천 임베딩에 넣는 글자를 만들 때 쓴다(트랜잭션 밖에서도 읽힘, T120). */
    @Query("select t.name from PostTag pt join pt.tag t where pt.post.id = :postId order by t.name")
    List<String> findTagNames(@Param("postId") Long postId);

    /** 공감 수 늘리기·줄이기 (SOC-01). 댓글 수와 같은 방식이다. */
    @Modifying(clearAutomatically = true)
    @Query("update Post p set p.likeCount = p.likeCount + :delta where p.id = :postId")
    int addLikeCount(@Param("postId") Long postId, @Param("delta") int delta);

    /**
     * 공감을 켜고 끈 뒤 응답에 넣을 지금 공감 수. lockById로 이미 잠근 행이라 더 기다리지 않는다.
     * PostgreSQL 기본 격리(READ COMMITTED)는 문장마다 새로 커밋된 값을 보므로 평범한 SELECT로도 되지만,
     * 격리 수준을 올려도(REPEATABLE READ면 트랜잭션 첫 스냅샷을 본다) 맞도록 잠그며 읽는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p.likeCount from Post p where p.id = :postId")
    int findLikeCount(@Param("postId") Long postId);

    /**
     * 글 행을 잠근다(SELECT ... FOR UPDATE). 공감·댓글을 넣고 수를 고치는 트랜잭션, 글을 고치는 요청(PostService)이
     * 맨 먼저 부른다.
     * 같은 글을 고치는 트랜잭션을 차례로 줄 세워, 뒤 요청이 앞 요청이 커밋한 기록과 수를 보고 판단하게 한다.
     * (MySQL에서는 공감·댓글 INSERT의 외래 키 공유 잠금과 수 UPDATE의 배타 잠금이 엇갈려 데드락도 났다. PostgreSQL의 외래 키 확인은
     * FOR KEY SHARE라 키가 아닌 칸의 UPDATE와 부딪히지 않지만, 줄 세우기는 그대로 필요하다.)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p.id from Post p where p.id = :postId")
    Optional<Long> lockById(@Param("postId") Long postId);

    /**
     * 여러 글 행을 잠근다(일괄 공개 범위 변경, 블로그 이동). 글을 읽고 고쳐 저장하기 전에 불러 예약 발행 작업과 줄을 세운다
     * (PostService.lockForChange와 같은 이유). 번호 순서로 잠가, 같은 글들을 다른 순서로 잠그는 두 요청이 서로를 기다리는
     * 데드락을 막는다. 빈 목록으로 부르지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p.id from Post p where p.id in :ids order by p.id")
    List<Long> lockAllById(@Param("ids") Collection<Long> ids);

}
