import { type FormEvent, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { loginUrl } from '../api/client'
import type { Blog } from '../api/types'
import { blogUrl, platformUrl } from '../app/host'
import { notificationLabel } from '../app/notifications'
import type { MeState } from '../app/useMe'
import { useUnreadCount } from '../app/useUnreadCount'
import { writeUrl } from '../app/writeLink'
import LogoutButton from './LogoutButton'
import SuspensionNotice from './SuspensionNotice'

/**
 * 블로그 주소(blog.com/@{address})의 머리글. 주인에게는 이 블로그의 글쓰기·관리 버튼이 보인다.
 * 남의 블로그에서 누르는 글쓰기는 내 대표 블로그의 글쓰기로, 블로그가 없으면 개설 안내로 간다 (AUTH-04).
 * 내 대표 블로그가 아닌 곳에서는 '내 블로그'(대표 블로그, BLOG-08), 서비스 관리자에게는 서비스 관리(ADMIN-01).
 * 로그인한 채로 정지된 회원이면 머리글 아래에 정지 사유를 띄운다(ADMIN-02).
 * 메뉴의 RSS는 이 블로그의 피드(/@{address}/rss)를 연다(SUB-07).
 */
export default function BlogHeader({ blog, me }: { blog: Blog; me: MeState }) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [q, setQ] = useState(params.get('q') ?? '')
  const unread = useUnreadCount(me)

  function search(event: FormEvent) {
    event.preventDefault()
    if (q.trim()) {
      navigate(`/search?${new URLSearchParams({ q: q.trim() })}`)
    }
  }

  const primaryBlog = me.status === 'member' ? me.me.primaryBlog : null
  return (
    <>
    <header className="site-head">
      <a className="logo" href={platformUrl('/')} title="now-log 홈">now-log</a>
      <Link className="blog-name" to="/">{blog.name}</Link>
      <nav className="nav-links">
        <Link to="/">홈</Link><Link to="/guestbook">방명록</Link>
        {/* 피드는 React 화면이 아니라 서버가 주는 XML이라 Link가 아니라 a로 연다 (SUB-07) */}
        <a href={blogUrl(blog.address, '/rss')} type="application/rss+xml" title={`${blog.name} RSS 피드`}>RSS</a>
      </nav>
      <span className="grow" />
      <form className="search-box" role="search" onSubmit={search}>
        <input type="search" value={q} maxLength={100} placeholder="이 블로그에서 검색" aria-label="블로그 내 검색"
               onChange={(event) => setQ(event.target.value)} />
        <button className="btn" type="submit">검색</button>
      </form>
      {me.status === 'anonymous' && <a className="btn" href={loginUrl()}>로그인</a>}
      {me.status === 'member' && (
        <div className="row">
          {blog.viewer.isOwner
            ? <Link className="btn primary" to="/manage/write">글쓰기</Link>
            : <a className="btn primary" href={writeUrl(me.me)}>글쓰기</a>}
          {blog.viewer.isOwner && <Link className="btn" to="/manage">관리</Link>}
          {primaryBlog && primaryBlog.address !== blog.address
            && <a className="btn" href={blogUrl(primaryBlog.address)}>내 블로그</a>}
          <a className="btn" href={platformUrl('/me/notifications')}>{notificationLabel(unread)}</a>
          <a className="btn" href={platformUrl('/me')} title={me.me.nickname}>마이페이지</a>
          {me.me.role === 'ADMIN' && <a className="btn" href={platformUrl('/admin')}>서비스 관리</a>}
          <LogoutButton />
        </div>
      )}
    </header>
    {me.status === 'anonymous' && me.suspension && (
      <div className="page"><SuspensionNotice suspension={me.suspension} /></div>
    )}
    </>
  )
}
