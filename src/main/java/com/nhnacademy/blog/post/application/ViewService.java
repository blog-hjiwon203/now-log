package com.nhnacademy.blog.post.application;

import com.nhnacademy.blog.post.domain.PostRepository;
import com.nhnacademy.blog.post.domain.ViewLog;
import com.nhnacademy.blog.post.domain.ViewLogRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회 기록과 조회수 (T053·T141, POST-09). 같은 조회자가 5분 안에 다시 열면 세지 않는다(Q2).
 * 볼 수 있는 글인지는 부르는 쪽이 먼저 확인한다(PostReadService.readable, 다른 트랜잭션).
 * <p>
 * "5분 안에 본 적 있나"는 Redis {@code SET key 1 NX EX 300} 한 번으로 정한다(T141).
 * NX는 키가 없을 때만 넣고, 넣었는지를 돌려준다. Redis는 명령을 하나씩 차례로 처리하므로 같은 사람이
 * 새로고침을 연타해 요청 여러 개가 동시에 와도(앱이 두 대여도) 키를 넣는 데 성공하는 것은 하나뿐이고, 그 요청만 센다.
 * 5분이 지나면 TTL로 키가 저절로 사라져 다시 센다.
 * <p>
 * 전에는 DB에서 글 행을 잠그고(SELECT ... FOR UPDATE) view_log에 5분 안 기록이 있는지 본 뒤 넣었다.
 * 그 잠금은 조회자가 아니라 글 단위라, 인기 글을 보는 모든 사람의 조회가 한 줄로 서고 같은 글의 공감·댓글도 기다렸다.
 * 이제 DB에는 센 조회만 간다(view_log 한 행 + 조회수 한 줄 UPDATE). view_log는 인기 점수·통계가 그대로 쓴다.
 */
@Service
public class ViewService {

    /** 이 시간 안에 같은 조회자가 다시 열면 한 번으로 본다 (spec Q2). */
    public static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(5);

    /** 5분 안에 센 조회 표시. 키 = view:seen:{글 id}:{조회자 키}, TTL = DUPLICATE_WINDOW. */
    public static final String SEEN_PREFIX = "view:seen:";

    private final PostRepository postRepository;
    private final ViewLogRepository viewLogRepository;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public ViewService(PostRepository postRepository, ViewLogRepository viewLogRepository, StringRedisTemplate redis,
                       Clock clock) {
        this.postRepository = postRepository;
        this.viewLogRepository = viewLogRepository;
        this.redis = redis;
        this.clock = clock;
    }

    /** 5분 안에 센 적이 없으면 기록을 남기고 조회수를 올린다. 셌으면 true. */
    @Transactional
    public boolean record(Long postId, String viewerKey) {
        String seenKey = seenKey(postId, viewerKey);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(seenKey, "1", DUPLICATE_WINDOW))) {
            return false;
        }
        try {
            viewLogRepository.save(ViewLog.of(postRepository.getReferenceById(postId), viewerKey,
                    LocalDateTime.now(clock)));
            postRepository.increaseViewCount(postId);
        } catch (RuntimeException e) {
            // DB에 못 남겼으면 표시도 지워서, 다시 열었을 때 센다
            redis.delete(seenKey);
            throw e;
        }
        return true;
    }

    public static String seenKey(Long postId, String viewerKey) {
        return SEEN_PREFIX + postId + ":" + viewerKey;
    }

}
