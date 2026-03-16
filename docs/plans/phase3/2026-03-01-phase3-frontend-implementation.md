# Phase 3: Front-End (React SPA) — Implementation Plan

**Version:** 2.0
**Last Updated:** 2026-03-16

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the React single-page application with TypeScript — auth, post browsing/creation, comments, likes, notifications, admin dashboard — connecting to the Phase 2 REST API.

**Architecture:** Vite + React 18 + TypeScript. React Query for server state. Axios for HTTP. React Router v6 for routing. Tailwind CSS v4 for styling. AuthContext for auth state. Markdown editor with live preview via react-markdown + rehype-sanitize.

**Tech Stack:** React 18, TypeScript, Vite, Tailwind CSS v4, React Router v6, TanStack Query (React Query), Axios, React Hook Form, react-markdown, rehype-sanitize, Vitest, React Testing Library, MSW

**Reference:** Design document at `docs/plans/design/2026-02-27-java-migration-design.md` (v7.0) — Section 3 (Front-End Structure).

**Prerequisite:** Phase 2 must be complete (full REST API running).

---

### Task 1: Initialize Vite + React + TypeScript Project

**Files:**
- Create: `frontend/` project via Vite scaffolding
- Modify: `frontend/vite.config.ts` — add API proxy
- Modify: `frontend/tsconfig.json`

**Step 1: Scaffold the project**

```bash
cd /path/to/blog-platform
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install
```

**Step 2: Install all dependencies**

```bash
npm install react-router-dom @tanstack/react-query axios react-hook-form \
  react-markdown rehype-sanitize tailwindcss @tailwindcss/typography \
  postcss autoprefixer
npm install -D vitest @testing-library/react @testing-library/jest-dom \
  @testing-library/user-event msw jsdom @types/react @types/react-dom
```

**Step 3: Configure Vite proxy**

Modify `frontend/vite.config.ts`:
```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
})
```

**Step 4: Configure Tailwind CSS v4**

Configure Tailwind v4 per their docs. Add `@import "tailwindcss";` to `src/index.css`. Tailwind v4 uses CSS-based configuration — no `tailwind.config.js` file is needed.

**Step 5: Verify dev server starts**

Run: `cd frontend && npm run dev`
Expected: Vite dev server at `http://localhost:5173`, proxy forwards `/api` to `localhost:8080`.

**Step 6: Commit**

```bash
git add frontend/
git commit -m "feat: initialize React + TypeScript + Vite project with dependencies"
```

---

### Task 2: TypeScript Types — Mirror Back-End DTOs

**Files:**
- Create: `frontend/src/types/api.ts`
- Create: `frontend/src/types/user.ts`
- Create: `frontend/src/types/post.ts`
- Create: `frontend/src/types/comment.ts`
- Create: `frontend/src/types/notification.ts`

**Step 1: Write type definitions**

`frontend/src/types/api.ts`:
```typescript
export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message: string | null;
  timestamp: string;
}

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}
```

`frontend/src/types/user.ts`:
```typescript
export const VALID_ROLES = ['ADMIN', 'AUTHOR', 'USER'] as const;
export type Role = typeof VALID_ROLES[number];

export interface User {
  accountId: number;
  username: string;
  email: string;
  role: Role;
  isVip: boolean;
  emailVerified: boolean;
}

export interface UserProfile {
  accountId: number;
  username: string;
  firstName: string | null;
  lastName: string | null;
  bio: string | null;
  profilePicUrl: string | null;
}

export interface AuthorProfile {
  authorId: number;
  displayName: string;
  biography: string | null;
  expertise: string | null;
  socialLinks: Record<string, string>;
  postCount: number;
}
```

`frontend/src/types/post.ts`:
```typescript
export interface PostSummary {
  postId: number;
  title: string;
  authorName: string;
  categoryName: string;
  tags: string[];
  likeCount: number;
  commentCount: number;
  isPremium: boolean;
  createdAt: string;
}

export interface PostDetail {
  postId: number;
  title: string;
  content: string;
  authorId: number;
  authorName: string;
  categoryName: string;
  tags: string[];
  likeCount: number;
  isPremium: boolean;
  hasLiked: boolean;
  hasRead: boolean;
  hasSaved: boolean;
  createdAt: string;
  updatedAt: string;
}
```

`frontend/src/types/comment.ts`:
```typescript
export interface Comment {
  commentId: number;
  content: string;
  username: string;
  createdAt: string;
  replies: Comment[];
}
```

`frontend/src/types/notification.ts`:
```typescript
export interface Notification {
  notificationId: number;
  message: string;
  isRead: boolean;
  createdAt: string;
}
```

**Step 2: Verify it compiles**

Run: `cd frontend && npx tsc --noEmit`

**Step 3: Commit**

```bash
git add frontend/src/types/
git commit -m "feat: add TypeScript types mirroring back-end DTOs"
```

---

### Task 3: Common Components — LoadingSpinner, ErrorMessage, ErrorBoundary, ConfirmDialog

> **Moved up from original Task 14.** These components are imported by nearly every page/task that follows, so they must exist before Tasks 6+.

**Files:**
- Create: `frontend/src/components/common/LoadingSpinner.tsx`
- Create: `frontend/src/components/common/ErrorMessage.tsx`
- Create: `frontend/src/components/common/ErrorBoundary.tsx`
- Create: `frontend/src/components/common/ConfirmDialog.tsx`
- Create: `frontend/src/components/common/Pagination.tsx`

**Step 1: Implement**

Simple, reusable UI components. `ConfirmDialog` used for delete confirmations (posts, comments).

`ErrorBoundary`: a React error boundary wrapping the route outlet. Catches render errors, displays a user-friendly fallback with a "Try Again" button that resets error state. Prevents the entire app from white-screening on a single component error.

**Step 2: Commit**

```bash
git add frontend/src/components/common/
git commit -m "feat: add common UI components (LoadingSpinner, ErrorMessage, ErrorBoundary, ConfirmDialog, Pagination)"
```

---

### Task 4: Axios Client + API Service Layer

**Files:**
- Create: `frontend/src/api/client.ts`
- Create: `frontend/src/api/validate.ts`
- Create: `frontend/src/api/auth.ts`
- Create: `frontend/src/api/posts.ts`
- Create: `frontend/src/api/comments.ts`
- Create: `frontend/src/api/likes.ts`
- Create: `frontend/src/api/users.ts`
- Create: `frontend/src/api/categories.ts`
- Create: `frontend/src/api/tags.ts`
- Create: `frontend/src/api/notifications.ts`
- Create: `frontend/src/api/subscriptions.ts`

**Step 1: Create response validation utility**

`frontend/src/api/validate.ts`:
```typescript
import { VALID_ROLES, type User } from '../types/user';

/**
 * Runtime validation at the API boundary. TypeScript types are erased at
 * runtime, so we validate critical fields (especially role strings) to
 * catch back-end contract drift early.
 */
export function validateUser(data: unknown): User {
  const obj = data as Record<string, unknown>;
  if (!obj || typeof obj !== 'object') throw new Error('Invalid user response');
  if (typeof obj.role !== 'string' || !VALID_ROLES.includes(obj.role as any)) {
    console.error('Unexpected role value from API:', obj.role);
    throw new Error(`Invalid role: ${obj.role}`);
  }
  return obj as User;
}
```

**Step 2: Create Axios instance**

`frontend/src/api/client.ts`:
```typescript
import axios from 'axios';

const client = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
});

// Read CSRF token from cookie and attach to mutation requests
client.interceptors.request.use((config) => {
  const csrfToken = document.cookie
    .split('; ')
    .find((row) => row.startsWith('XSRF-TOKEN='))
    ?.split('=')[1];
  if (csrfToken && config.method !== 'get') {
    config.headers['X-XSRF-TOKEN'] = decodeURIComponent(csrfToken);
  }
  return config;
});

// Global 401 handler — emits event instead of redirecting directly.
// AuthContext listens and decides whether to redirect (avoids infinite
// loop when getCurrentUser() returns 401 for unauthenticated users).
let onUnauthorized: (() => void) | null = null;

export function setOnUnauthorized(callback: (() => void) | null) {
  onUnauthorized = callback;
}

client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && onUnauthorized) {
      onUnauthorized();
    }
    return Promise.reject(error);
  }
);

export default client;
```

**Key changes from v1.0:**
- The 401 interceptor uses a callback pattern (`setOnUnauthorized`) instead of a hard `window.location.href = '/login'` redirect. AuthContext registers the callback and controls redirect logic, preventing the infinite redirect loop that occurred when `getCurrentUser()` returned 401 for unauthenticated users.

**Step 3: Create CSRF priming utility**

Spring Security 6 uses deferred CSRF token loading — the `XSRF-TOKEN` cookie is not set until a request triggers token generation. If the SPA's first action is a POST (login/register), the cookie won't exist yet and the request will fail with 403.

Add to `client.ts`:
```typescript
/**
 * Prime the CSRF token by making a lightweight GET request.
 * Call once during app initialization before any mutations are possible.
 */
export async function primeCsrfToken(): Promise<void> {
  try {
    await client.get('/auth/csrf');
  } catch {
    // Non-fatal — CSRF cookie may already exist from a prior session.
    // If it truly doesn't exist, the first POST will fail and the user
    // can retry after the GET has set the cookie.
  }
}
```

> **Back-end prerequisite:** Ensure a lightweight GET endpoint exists at `/api/v1/auth/csrf` (can return 204 No Content). Alternatively, piggyback on any existing public GET endpoint.

**Step 4: Create API service modules**

Each module exports typed functions. Example `auth.ts`:
```typescript
import client from './client';
import { ApiResponse } from '../types/api';
import { User } from '../types/user';
import { validateUser } from './validate';

export const login = async (username: string, password: string): Promise<User> => {
  const res = await client.post<ApiResponse<User>>('/auth/login', { username, password });
  return validateUser(res.data.data);
};

export const register = async (username: string, email: string, password: string): Promise<User> => {
  const res = await client.post<ApiResponse<User>>('/auth/register', { username, email, password });
  return validateUser(res.data.data);
};

export const logout = () => client.post<ApiResponse<null>>('/auth/logout');

export const getCurrentUser = async (): Promise<User> => {
  const res = await client.get<ApiResponse<User>>('/auth/me');
  return validateUser(res.data.data);
};
```

Similar patterns for posts, comments, likes, users, categories, tags, notifications, subscriptions. Note: `baseURL: '/api/v1'` assumes a reverse proxy (Nginx) in production — see Phase 4 deployment plan.

**Step 5: Verify it compiles**

Run: `cd frontend && npx tsc --noEmit`

**Step 6: Commit**

```bash
git add frontend/src/api/
git commit -m "feat: add Axios client with CSRF handling, response validation, and API service layer"
```

---

### Task 5: MSW Setup for Component Tests

> **Moved up from original Task 16.** Tests in Tasks 6+ need MSW to mock API calls.

**Files:**
- Create: `frontend/src/test/setup.ts`
- Create: `frontend/src/test/mocks/handlers.ts`
- Create: `frontend/src/test/mocks/server.ts`

**Step 1: Set up MSW**

Mock API handlers for all endpoints used in tests. Setup MSW server in test setup file.

**Step 2: Verify setup compiles**

Run: `cd frontend && npx tsc --noEmit`

**Step 3: Commit**

```bash
git add frontend/src/test/
git commit -m "feat: add MSW mock server for component tests"
```

---

### Task 6: AuthContext + useAuth Hook

**Files:**
- Create: `frontend/src/context/AuthContext.tsx`
- Create: `frontend/src/hooks/useAuth.ts` — re-exports `useAuth` from AuthContext for convenience
- Create: `frontend/src/hooks/__tests__/useAuth.test.tsx`

**Step 1: Write failing test**

```typescript
import { renderHook, act } from '@testing-library/react';
import { AuthProvider } from '../../context/AuthContext';
import { useAuth } from '../useAuth';

test('useAuth returns null user when not logged in', () => {
  const { result } = renderHook(() => useAuth(), {
    wrapper: AuthProvider,
  });
  expect(result.current.user).toBeNull();
  expect(result.current.isAuthenticated).toBe(false);
});
```

**Step 2: Implement AuthContext**

`frontend/src/context/AuthContext.tsx` — defines `AuthProvider` and `useAuth`:
```typescript
import { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react';
import { User } from '../types/user';
import * as authApi from '../api/auth';
import { setOnUnauthorized } from '../api/client';

interface AuthContextType {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const handleUnauthorized = useCallback(() => {
    setUser(null);
    // Only redirect if not already on a public page
    const publicPaths = ['/', '/login', '/register', '/posts', '/authors'];
    const isPublic = publicPaths.some((p) => window.location.pathname === p)
      || window.location.pathname.startsWith('/posts/')
      || window.location.pathname.startsWith('/authors/');
    if (!isPublic) {
      window.location.href = '/login';
    }
  }, []);

  useEffect(() => {
    setOnUnauthorized(handleUnauthorized);
    authApi.getCurrentUser()
      .then((user) => setUser(user))
      .catch(() => setUser(null))
      .finally(() => setIsLoading(false));
    return () => setOnUnauthorized(null);
  }, [handleUnauthorized]);

  const login = async (username: string, password: string) => {
    const user = await authApi.login(username, password);
    setUser(user);
  };

  const register = async (username: string, email: string, password: string) => {
    const user = await authApi.register(username, email, password);
    setUser(user);
  };

  const logout = async () => {
    await authApi.logout();
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, isAuthenticated: !!user, isLoading, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider');
  return context;
}
```

**Key changes from v1.0:**
- `useAuth` is defined in `AuthContext.tsx` (single canonical location). `hooks/useAuth.ts` re-exports it: `export { useAuth } from '../context/AuthContext';`
- The 401 handler uses a callback pattern — `AuthContext` registers `handleUnauthorized` via `setOnUnauthorized()` and controls redirect logic. The `getCurrentUser()` 401 failure is handled gracefully in the `catch` block without triggering a redirect loop.
- Auth API functions now return validated `User` objects directly (via `validateUser`), so `res.data.data` unwrapping is no longer needed here.

`frontend/src/hooks/useAuth.ts`:
```typescript
export { useAuth } from '../context/AuthContext';
```

**Step 3: Run test, verify pass**

Run: `cd frontend && npx vitest run`

**Step 4: Commit**

```bash
git add frontend/src/context/ frontend/src/hooks/
git commit -m "feat: add AuthContext and useAuth hook with callback-based 401 handling"
```

---

### Task 7: App Routing + Layout Components

**Files:**
- Create: `frontend/src/App.tsx`
- Create: `frontend/src/components/layout/Layout.tsx`
- Create: `frontend/src/components/layout/Header.tsx`
- Create: `frontend/src/components/layout/Footer.tsx`
- Create: `frontend/src/components/auth/ProtectedRoute.tsx`

**Step 1: Configure QueryClient with production defaults**

Define `queryClient` in `App.tsx` with sensible defaults:
```typescript
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 60_000,           // 1 minute — avoids refetch on every mount
      retry: 2,
      refetchOnWindowFocus: false,  // explicit refetch preferred
    },
  },
});
```

**Step 2: Prime CSRF token on app init**

In `App.tsx`, call `primeCsrfToken()` once on mount to ensure the CSRF cookie exists before any mutation:
```typescript
import { primeCsrfToken } from './api/client';

useEffect(() => {
  primeCsrfToken();
}, []);
```

**Step 3: Implement routing**

`App.tsx` sets up React Router with all routes. Wrap the route outlet with `ErrorBoundary`:
```typescript
<AuthProvider>
  <QueryClientProvider client={queryClient}>
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/posts/:id" element={<PostPage />} />
          <Route path="/authors" element={<AuthorsPage />} />
          <Route path="/authors/:id" element={<AuthorPage />} />
          <Route element={<ProtectedRoute />}>
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/saved" element={<SavedPostsPage />} />
            <Route path="/notifications" element={<NotificationsPage />} />
          </Route>
          <Route element={<ProtectedRoute requiredRoles={['AUTHOR', 'ADMIN']} />}>
            <Route path="/create-post" element={<CreatePostPage />} />
            <Route path="/edit-post/:id" element={<EditPostPage />} />
          </Route>
          <Route element={<ProtectedRoute requiredRoles={['ADMIN']} />}>
            <Route path="/admin" element={<AdminDashboard />} />
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  </QueryClientProvider>
</AuthProvider>
```

**Key changes from v1.0:**
- `ProtectedRoute` accepts `requiredRoles` as an array of `Role` strings (e.g., `['AUTHOR', 'ADMIN']`) instead of a single `requiredRole` string. This allows create/edit post routes to be accessible to both authors and admins.
- Create/edit post routes are now guarded with `requiredRoles={['AUTHOR', 'ADMIN']}`.
- `QueryClient` is configured with `staleTime`, `retry`, and `refetchOnWindowFocus` defaults.
- CSRF token is primed on app mount.

`ProtectedRoute`: checks `useAuth()`. Shows `LoadingSpinner` while `isLoading` is true. Redirects to `/login` if not authenticated. If `requiredRoles` prop is provided, checks that `user.role` is in the array — renders an "Access Denied" message if not.

`Header`: nav bar with logo, links, user menu, notifications bell (badge with unread count). "Create Post" link only visible when `user.role` is `AUTHOR` or `ADMIN`. Notification bell uses `aria-label` with unread count for screen reader support.

`Layout`: wraps pages with Header + Footer using React Router's `<Outlet />`. Wraps the outlet with `ErrorBoundary`.

**Step 4: Write test for ProtectedRoute**

```typescript
test('ProtectedRoute redirects to login when not authenticated', () => {
  // Render ProtectedRoute with no user → verify redirect to /login
});

test('ProtectedRoute renders children when authenticated', () => {
  // Render ProtectedRoute with user → verify children rendered
});

test('ProtectedRoute shows access denied for insufficient role', () => {
  // Render ProtectedRoute with requiredRoles={['ADMIN']} and USER role → verify access denied
});
```

**Step 5: Verify it compiles and tests pass**

**Step 6: Commit**

```bash
git commit -m "feat: add routing, layout, Header, Footer, ProtectedRoute with role array support"
```

---

### Task 8: Login and Register Pages

**Files:**
- Create: `frontend/src/pages/LoginPage.tsx`
- Create: `frontend/src/pages/RegisterPage.tsx`
- Create: `frontend/src/components/auth/LoginForm.tsx`
- Create: `frontend/src/components/auth/RegisterForm.tsx`
- Create: `frontend/src/components/auth/__tests__/LoginForm.test.tsx`

**Step 1: Write failing test**

```typescript
test('LoginForm submits credentials and calls login', async () => {
  // Render LoginForm, fill in username/password, click submit
  // Verify login API called with correct credentials
});

test('LoginForm shows error on failed login', async () => {
  // Mock login to reject → verify error message displayed
});
```

**Step 2: Implement forms using React Hook Form**

`LoginForm`: username + password fields, submit button, error display. Uses `useAuth().login()`. All form inputs use `<label>` elements (not just placeholders) for accessibility.

`RegisterForm`: username + email + password + confirm password fields. Validates password requirements (8+ chars, uppercase, lowercase, digit). Uses `useAuth().register()`. All form inputs use `<label>` elements.

Pages wrap forms with layout.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Login and Register pages with form validation"
```

---

### Task 9: Home Page — Post Feed with Filters and Pagination

**Files:**
- Create: `frontend/src/pages/HomePage.tsx`
- Create: `frontend/src/components/posts/PostCard.tsx`
- Create: `frontend/src/components/posts/PostFilters.tsx`
- Create: `frontend/src/components/posts/PremiumBadge.tsx`
- Create: `frontend/src/hooks/usePosts.ts`
- Create: `frontend/src/components/posts/__tests__/PostCard.test.tsx`

**Step 1: Write failing test**

```typescript
test('PostCard renders title, author, like count', () => {
  render(<PostCard post={mockPost} />);
  expect(screen.getByText('Test Post')).toBeInTheDocument();
  expect(screen.getByText('by testauthor')).toBeInTheDocument();
});

test('PostCard shows PremiumBadge for premium posts', () => {
  render(<PostCard post={{ ...mockPost, isPremium: true }} />);
  expect(screen.getByText('VIP')).toBeInTheDocument();
});
```

**Step 2: Implement**

`usePosts` hook uses React Query:
```typescript
export function usePosts(filters: PostFilters) {
  return useQuery({
    queryKey: ['posts', filters],
    queryFn: () => postsApi.getPosts(filters),
  });
}
```

`PostFilters` component: dropdowns for category/tag/author, search input. Changes update URL query params and trigger new fetch. **Text search input uses 300ms debounce** to avoid excessive API calls on each keystroke.

`HomePage` composes these: PostFilters + list of PostCards + Pagination.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Home page with post feed, debounced filters, and pagination"
```

---

### Task 10: Post Detail Page — Read, Like, Save, Comments

**Files:**
- Create: `frontend/src/pages/PostPage.tsx`
- Create: `frontend/src/components/posts/PostDetail.tsx`
- Create: `frontend/src/components/comments/CommentList.tsx`
- Create: `frontend/src/components/comments/CommentItem.tsx`
- Create: `frontend/src/components/comments/CommentForm.tsx`
- Create: `frontend/src/hooks/useComments.ts`
- Create: `frontend/src/hooks/useLike.ts`
- Create: `frontend/src/hooks/useSave.ts`

**Step 1: Write failing tests**

```typescript
test('PostDetail renders Markdown content safely', () => {
  // Content with <script> tag → verify script not in DOM
  // Uses react-markdown + rehype-sanitize
});

test('CommentForm is disabled if post not read', () => {
  // hasRead = false → form disabled with message
});

test('CommentForm shows character count', () => {
  // Type 200 chars → shows "200/250"
});
```

**Step 2: Implement**

`PostPage`: On mount, calls `postsApi.markAsRead(postId)` via a `useEffect` to trigger read-tracking. This ensures `hasRead` becomes `true` so the `CommentForm` is enabled.

`PostDetail`: renders Markdown via `react-markdown` + `rehype-sanitize`. Shows like button (toggle), save/bookmark button, share. **NEVER uses `dangerouslySetInnerHTML`.**

`useLike` and `useSave` hooks: implement **optimistic updates** using React Query's `useMutation` with `onMutate` for instant UI feedback:
```typescript
export function useLike(postId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => likesApi.toggleLike(postId),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: ['post', postId] });
      const previous = queryClient.getQueryData<PostDetail>(['post', postId]);
      queryClient.setQueryData<PostDetail>(['post', postId], (old) =>
        old ? { ...old, hasLiked: !old.hasLiked, likeCount: old.likeCount + (old.hasLiked ? -1 : 1) } : old
      );
      return { previous };
    },
    onError: (_err, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(['post', postId], context.previous);
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['post', postId] }),
  });
}
```

Same pattern for `useSave` (toggling `hasSaved`).

`CommentList`: renders threaded comments recursively, **with a max depth of 5 levels**. Beyond depth 5, replies are flattened or a "Continue thread" link is shown to prevent excessive DOM nesting and indentation overflow.

`CommentItem`: single comment with reply button, delete button (if owner).

`CommentForm`: textarea with 250-char limit and character counter. Disabled if user hasn't read the post.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Post detail page with Markdown rendering, optimistic likes/saves, comments"
```

---

### Task 11: Post Editor — Create and Edit Pages (Authors Only)

**Files:**
- Create: `frontend/src/pages/CreatePostPage.tsx`
- Create: `frontend/src/pages/EditPostPage.tsx`
- Create: `frontend/src/components/posts/PostForm.tsx`

**Step 1: Write failing test**

```typescript
test('PostForm shows live Markdown preview', async () => {
  render(<PostForm onSubmit={vi.fn()} />);
  const textarea = screen.getByRole('textbox', { name: /content/i });
  await userEvent.type(textarea, '# Hello World');
  expect(screen.getByText('Hello World')).toBeInTheDocument();
});
```

> Note: Uses `vi.fn()` (Vitest), not `jest.fn()`.

**Step 2: Implement**

`PostForm`: two-column layout — Markdown textarea on left, live preview on right. Uses `react-markdown` + `rehype-sanitize` for preview. Fields: title, content, category (dropdown), tags (multi-select), isPremium checkbox.

`CreatePostPage` wraps `PostForm`, calls `postsApi.createPost()`.

`EditPostPage` loads existing post, pre-fills `PostForm`, calls `postsApi.updatePost()`.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Post editor with Markdown textarea and live preview"
```

---

### Task 12: User Profile + Saved Posts Pages

**Files:**
- Create: `frontend/src/pages/ProfilePage.tsx`
- Create: `frontend/src/pages/SavedPostsPage.tsx`
- Create: `frontend/src/components/users/ProfileCard.tsx`
- Create: `frontend/src/components/users/ProfileEditForm.tsx`

**Step 1: Implement**

`ProfilePage`: shows user profile (name, bio, avatar), edit button. Uses `ProfileEditForm` in edit mode.

`SavedPostsPage`: paginated list of bookmarked posts using `PostCard`.

**Step 2: Write tests for ProfileCard**

**Step 3: Commit**

```bash
git commit -m "feat: add Profile and Saved Posts pages"
```

---

### Task 13: Notifications Page + Polling

**Files:**
- Create: `frontend/src/pages/NotificationsPage.tsx`
- Create: `frontend/src/hooks/useNotifications.ts`

**Step 1: Implement**

`useNotifications` hook:
```typescript
export function useNotifications() {
  return useQuery({
    queryKey: ['notifications'],
    queryFn: () => notificationsApi.getNotifications(),
    refetchInterval: 30_000, // 30-second polling
    refetchIntervalInBackground: false, // stop polling when tab is hidden
    retry: (failureCount) => failureCount < 3,
    retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 30_000), // exponential backoff
  });
}
```

`NotificationsPage`: list of notifications, "mark as read" button per item, "mark all as read" button. Notification bell in Header uses `aria-label` with count (e.g., `aria-label="3 unread notifications"`).

Header notification bell shows unread count badge from this hook.

**Step 2: Write test**

```typescript
test('NotificationsPage shows unread notifications', () => {
  // Mock notifications response → verify list rendered
});
```

**Step 3: Commit**

```bash
git commit -m "feat: add Notifications page with 30s polling, backoff, and background pause"
```

---

### Task 14: Admin Dashboard — Categories, Tags, Role Management

**Files:**
- Create: `frontend/src/pages/AdminDashboard.tsx`

**Step 1: Implement**

Tabs or sections:
- **Categories**: list, create, edit, delete
- **Tags**: list, create
- **Users**: search users, assign AUTHOR/USER role
- **Deleted Posts**: list soft-deleted posts, restore button

All behind `ProtectedRoute requiredRoles={['ADMIN']}`.

**Step 2: Write test**

```typescript
test('AdminDashboard only accessible to ADMIN', () => {
  // Render with USER role → redirect to home
});
```

**Step 3: Commit**

```bash
git commit -m "feat: add Admin dashboard with category/tag/user management"
```

---

### Task 15: Author Listing and Author Profile Pages

**Files:**
- Create: `frontend/src/pages/AuthorsPage.tsx`
- Create: `frontend/src/pages/AuthorPage.tsx`

**Step 1: Implement**

`AuthorsPage`: list all authors with post counts.

`AuthorPage`: author detail — biography, social links, paginated posts by that author.

**Step 2: Commit**

```bash
git commit -m "feat: add Author listing and profile pages"
```

---

### Task 16: Utility Functions

**Files:**
- Create: `frontend/src/utils/formatDate.ts`
- Create: `frontend/src/utils/truncateText.ts`
- Create: `frontend/src/utils/__tests__/formatDate.test.ts`
- Create: `frontend/src/utils/__tests__/truncateText.test.ts`

**Step 1: Write failing tests**

```typescript
test('formatDate formats ISO string to readable date', () => {
  expect(formatDate('2026-03-01T14:30:00Z')).toBe('Mar 1, 2026');
});

test('truncateText truncates at word boundary', () => {
  expect(truncateText('Hello World Foo Bar', 15)).toBe('Hello World...');
});
```

**Step 2: Implement**

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add date formatting and text truncation utils"
```

---

### Task 17: Cypress E2E Tests — Critical User Flows

**Files:**
- Create: `frontend/cypress/e2e/auth.cy.ts`
- Create: `frontend/cypress/e2e/post-flow.cy.ts`
- Create: `frontend/cypress/e2e/author-flow.cy.ts`
- Create: `frontend/cypress.config.ts`

**Step 1: Install Cypress**

```bash
cd frontend && npm install -D cypress
```

**Step 2: Write E2E tests**

`auth.cy.ts`:
```typescript
describe('Auth Flow', () => {
  it('registers, logs in, browses posts, reads a post, comments, logs out', () => {
    cy.visit('/register');
    cy.get('[name=username]').type('e2euser');
    cy.get('[name=email]').type('e2e@test.com');
    cy.get('[name=password]').type('Password1');
    cy.get('button[type=submit]').click();
    cy.url().should('eq', Cypress.config().baseUrl + '/');
    // Browse posts, click first post, add comment, logout
  });
});
```

`post-flow.cy.ts`: Author login → create post → edit → delete.

**Step 3: Run E2E tests against running stack**

Run: `cd frontend && npx cypress run`
Expected: All E2E tests pass.

**Step 4: Commit**

```bash
git commit -m "test: add Cypress E2E tests for critical user flows"
```

---

### Task 18: Full Phase 3 Verification

**Step 1: Run all front-end tests**

```bash
cd frontend && npx vitest run && npx cypress run
```

**Step 2: Manual smoke test**

Open `http://localhost:5173` with back-end running. Walk through:
1. Register → Login → Browse posts → Read a post → Comment → Like → Save → Logout
2. Login as AUTHOR → Create post (Markdown) → Preview → Publish → Edit → Delete
3. Login as ADMIN → Manage categories → Promote user to AUTHOR → View deleted posts → Restore

**Step 3: Commit any fixes**

```bash
git commit -m "fix: phase 3 smoke test fixes"
```

---

## Summary

Phase 3 delivers (18 tasks):
- Vite + React + TypeScript project with Tailwind CSS v4
- Vite proxy for API requests during development
- TypeScript types mirroring back-end DTOs with runtime validation at API boundary
- Common UI components (LoadingSpinner, ErrorMessage, ErrorBoundary, ConfirmDialog, Pagination)
- Axios client with CSRF priming, callback-based 401 handling, and response validation
- MSW mock server for component tests
- API service layer for all endpoints
- AuthContext + useAuth hook with safe session-check flow
- React Router v6 routing with role-array protected routes and ErrorBoundary
- Login/Register pages with accessible form validation
- Home page with post feed, debounced filters, pagination, search
- Post detail page with Markdown rendering (react-markdown + rehype-sanitize), read-tracking, optimistic likes/saves, depth-limited threaded comments
- Post editor with Markdown textarea and live preview (Vitest-compatible tests)
- User profile and saved posts pages
- Notifications page with 30-second polling, exponential backoff, and background-tab pause
- Admin dashboard (categories, tags, users, deleted posts)
- Author listing and profile pages (both AuthorsPage and AuthorPage)
- Cypress E2E tests for critical flows

**Next plan:** Phase 4 (Production Deployment — VPS)

---

## Changelog

### v2.0 — 2026-03-16

Revision per [critical implementation review v1](./2026-03-01-phase3-frontend-implementation-critical-review-1.md).

**Critical fixes:**
- **[2.2] Fixed infinite 401 redirect loop:** Replaced hard `window.location.href = '/login'` in Axios 401 interceptor with a callback pattern (`setOnUnauthorized`). AuthContext registers the callback and controls redirect logic, preventing the loop when `getCurrentUser()` returns 401 for unauthenticated users. (Task 4, Task 6)
- **[2.3] Fixed CSRF token initialization:** Added `primeCsrfToken()` utility that makes a lightweight GET request on app mount to ensure the `XSRF-TOKEN` cookie exists before any POST. Spring Security 6's deferred CSRF loading would otherwise cause 403 on login/register for new sessions. (Task 4, Task 7)
- **[2.1] Added runtime API response validation:** TypeScript types are erased at runtime. Added `validate.ts` with `validateUser()` that checks critical fields (especially `role` strings) at the API boundary. Auth API functions now validate responses before returning. (Task 4)
- **[2.4] Resolved duplicate `useAuth` definition:** `useAuth` is defined only in `AuthContext.tsx`. `hooks/useAuth.ts` re-exports it for import convenience. (Task 6)
- **[2.5] Added role-guarding for author routes:** `ProtectedRoute` now accepts `requiredRoles` as an array (e.g., `['AUTHOR', 'ADMIN']`). Create/edit post routes are guarded accordingly. Header conditionally shows "Create Post" link based on role. (Task 7)
- **[2.6] Reordered tasks for incremental builds:** Moved common components (old Task 14) to Task 3 and MSW setup (old Task 16) to Task 5. These are dependencies for Tasks 6+. (Task 3, Task 5)
- **[2.7] Added ErrorBoundary:** New `ErrorBoundary` component in common components, wrapping the route outlet in Layout. Catches render errors with user-friendly fallback and retry. (Task 3, Task 7)

**Minor fixes:**
- **[3.1]** Fixed `jest.fn()` → `vi.fn()` in PostForm test (Task 11)
- **[3.2]** Task 15 now explicitly creates both `AuthorsPage.tsx` (listing) and `AuthorPage.tsx` (detail) (Task 15)
- **[3.3]** Added optimistic updates for likes and saves via `useMutation.onMutate` with automatic rollback (Task 10)
- **[3.4]** Added `refetchIntervalInBackground: false` to notification polling (Task 13)
- **[3.5]** Added `QueryClient` configuration with `staleTime`, `retry`, `refetchOnWindowFocus` defaults (Task 7)
- **[3.6]** Added max depth of 5 for recursive comment rendering; beyond that, replies flatten or show "Continue thread" (Task 10)
- **[3.7]** Added 300ms debounce on text search input in `PostFilters` (Task 9)
- **[3.8]** Added accessibility requirements inline: `<label>` elements on forms, `aria-label` on icon buttons (notification bell, like/save), focus management notes (Tasks 8, 13)
- **[3.9]** Updated to Tailwind CSS v4 — uses `@import "tailwindcss"` instead of `@tailwind` directives, CSS-based config instead of `tailwind.config.js` (Task 1)
- **[3.10]** Added `postsApi.markAsRead(postId)` call in `PostPage` `useEffect` on mount to trigger read-tracking (Task 10)
- **[3.11]** Added note that `baseURL: '/api/v1'` assumes reverse proxy in production — deferred to Phase 4 (Task 4)

### v1.0 — 2026-03-01

Initial implementation plan.
