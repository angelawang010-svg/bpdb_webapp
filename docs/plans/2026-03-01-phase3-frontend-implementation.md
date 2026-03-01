# Phase 3: Front-End (React SPA) — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the React single-page application with TypeScript — auth, post browsing/creation, comments, likes, notifications, admin dashboard — connecting to the Phase 2 REST API.

**Architecture:** Vite + React 18 + TypeScript. React Query for server state. Axios for HTTP. React Router v6 for routing. Tailwind CSS for styling. AuthContext for auth state. Markdown editor with live preview via react-markdown + rehype-sanitize.

**Tech Stack:** React 18, TypeScript, Vite, Tailwind CSS, React Router v6, TanStack Query (React Query), Axios, React Hook Form, react-markdown, rehype-sanitize, Vitest, React Testing Library, MSW

**Reference:** Design document at `docs/plans/2026-02-27-java-migration-design.md` (v7.0) — Section 3 (Front-End Structure).

**Prerequisite:** Phase 2 must be complete (full REST API running).

---

### Task 1: Initialize Vite + React + TypeScript Project

**Files:**
- Create: `frontend/` project via Vite scaffolding
- Modify: `frontend/vite.config.ts` — add API proxy
- Modify: `frontend/tailwind.config.js`
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

**Step 4: Configure Tailwind CSS**

Initialize and configure Tailwind per their docs. Add `@tailwind base; @tailwind components; @tailwind utilities;` to `src/index.css`.

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
export interface User {
  accountId: number;
  username: string;
  email: string;
  role: 'ADMIN' | 'AUTHOR' | 'USER';
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

### Task 3: Axios Client + API Service Layer

**Files:**
- Create: `frontend/src/api/client.ts`
- Create: `frontend/src/api/auth.ts`
- Create: `frontend/src/api/posts.ts`
- Create: `frontend/src/api/comments.ts`
- Create: `frontend/src/api/likes.ts`
- Create: `frontend/src/api/users.ts`
- Create: `frontend/src/api/categories.ts`
- Create: `frontend/src/api/tags.ts`
- Create: `frontend/src/api/notifications.ts`
- Create: `frontend/src/api/subscriptions.ts`

**Step 1: Create Axios instance**

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

// Global 401 handler
client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default client;
```

**Step 2: Create API service modules**

Each module exports typed functions. Example `auth.ts`:
```typescript
import client from './client';
import { ApiResponse } from '../types/api';
import { User } from '../types/user';

export const login = (username: string, password: string) =>
  client.post<ApiResponse<User>>('/auth/login', { username, password });

export const register = (username: string, email: string, password: string) =>
  client.post<ApiResponse<User>>('/auth/register', { username, email, password });

export const logout = () => client.post<ApiResponse<null>>('/auth/logout');

export const getCurrentUser = () => client.get<ApiResponse<User>>('/auth/me');
```

Similar patterns for posts, comments, likes, users, categories, tags, notifications, subscriptions.

**Step 3: Verify it compiles**

Run: `cd frontend && npx tsc --noEmit`

**Step 4: Commit**

```bash
git add frontend/src/api/
git commit -m "feat: add Axios client with CSRF handling and API service layer"
```

---

### Task 4: AuthContext + useAuth Hook

**Files:**
- Create: `frontend/src/context/AuthContext.tsx`
- Create: `frontend/src/hooks/useAuth.ts`
- Create: `frontend/src/test/setup.ts`
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

```typescript
import { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { User } from '../types/user';
import * as authApi from '../api/auth';

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

  useEffect(() => {
    authApi.getCurrentUser()
      .then((res) => setUser(res.data.data))
      .catch(() => setUser(null))
      .finally(() => setIsLoading(false));
  }, []);

  const login = async (username: string, password: string) => {
    const res = await authApi.login(username, password);
    setUser(res.data.data);
  };

  const register = async (username: string, email: string, password: string) => {
    const res = await authApi.register(username, email, password);
    setUser(res.data.data);
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

**Step 3: Run test, verify pass**

Run: `cd frontend && npx vitest run`

**Step 4: Commit**

```bash
git add frontend/src/context/ frontend/src/hooks/ frontend/src/test/
git commit -m "feat: add AuthContext and useAuth hook"
```

---

### Task 5: App Routing + Layout Components

**Files:**
- Create: `frontend/src/App.tsx`
- Create: `frontend/src/components/layout/Layout.tsx`
- Create: `frontend/src/components/layout/Header.tsx`
- Create: `frontend/src/components/layout/Footer.tsx`
- Create: `frontend/src/components/auth/ProtectedRoute.tsx`

**Step 1: Implement routing**

`App.tsx` sets up React Router with all routes:
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
            <Route path="/create-post" element={<CreatePostPage />} />
            <Route path="/edit-post/:id" element={<EditPostPage />} />
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/saved" element={<SavedPostsPage />} />
            <Route path="/notifications" element={<NotificationsPage />} />
          </Route>
          <Route element={<ProtectedRoute requiredRole="ADMIN" />}>
            <Route path="/admin" element={<AdminDashboard />} />
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  </QueryClientProvider>
</AuthProvider>
```

`ProtectedRoute`: checks `useAuth()`, redirects to `/login` if not authenticated. If `requiredRole` prop, also checks role.

`Header`: nav bar with logo, links, user menu, notifications bell (badge with unread count).

`Layout`: wraps pages with Header + Footer using React Router's `<Outlet />`.

**Step 2: Write test for ProtectedRoute**

```typescript
test('ProtectedRoute redirects to login when not authenticated', () => {
  // Render ProtectedRoute with no user → verify redirect to /login
});

test('ProtectedRoute renders children when authenticated', () => {
  // Render ProtectedRoute with user → verify children rendered
});
```

**Step 3: Verify it compiles and tests pass**

**Step 4: Commit**

```bash
git commit -m "feat: add routing, layout, Header, Footer, ProtectedRoute"
```

---

### Task 6: Login and Register Pages

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

`LoginForm`: username + password fields, submit button, error display. Uses `useAuth().login()`.

`RegisterForm`: username + email + password + confirm password fields. Validates password requirements (8+ chars, uppercase, lowercase, digit). Uses `useAuth().register()`.

Pages wrap forms with layout.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Login and Register pages with form validation"
```

---

### Task 7: Home Page — Post Feed with Filters and Pagination

**Files:**
- Create: `frontend/src/pages/HomePage.tsx`
- Create: `frontend/src/components/posts/PostCard.tsx`
- Create: `frontend/src/components/posts/PostFilters.tsx`
- Create: `frontend/src/components/posts/PremiumBadge.tsx`
- Create: `frontend/src/components/common/Pagination.tsx`
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

`PostFilters` component: dropdowns for category/tag/author, search input. Changes update URL query params and trigger new fetch.

`Pagination` component: page numbers, prev/next buttons.

`HomePage` composes these: PostFilters + list of PostCards + Pagination.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Home page with post feed, filters, and pagination"
```

---

### Task 8: Post Detail Page — Read, Like, Save, Comments

**Files:**
- Create: `frontend/src/pages/PostPage.tsx`
- Create: `frontend/src/components/posts/PostDetail.tsx`
- Create: `frontend/src/components/comments/CommentList.tsx`
- Create: `frontend/src/components/comments/CommentItem.tsx`
- Create: `frontend/src/components/comments/CommentForm.tsx`
- Create: `frontend/src/hooks/useComments.ts`

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

`PostDetail`: renders Markdown via `react-markdown` + `rehype-sanitize`. Shows like button (toggle), save/bookmark button, share. **NEVER uses `dangerouslySetInnerHTML`.**

`CommentList`: renders threaded comments recursively.

`CommentItem`: single comment with reply button, delete button (if owner).

`CommentForm`: textarea with 250-char limit and character counter. Disabled if user hasn't read the post.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: add Post detail page with Markdown rendering, comments, likes"
```

---

### Task 9: Post Editor — Create and Edit Pages (Authors Only)

**Files:**
- Create: `frontend/src/pages/CreatePostPage.tsx`
- Create: `frontend/src/pages/EditPostPage.tsx`
- Create: `frontend/src/components/posts/PostForm.tsx`

**Step 1: Write failing test**

```typescript
test('PostForm shows live Markdown preview', async () => {
  render(<PostForm onSubmit={jest.fn()} />);
  const textarea = screen.getByRole('textbox', { name: /content/i });
  await userEvent.type(textarea, '# Hello World');
  expect(screen.getByText('Hello World')).toBeInTheDocument();
});
```

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

### Task 10: User Profile + Saved Posts Pages

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

### Task 11: Notifications Page + Polling

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
    retry: (failureCount) => failureCount < 3,
    retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 30_000), // exponential backoff
  });
}
```

`NotificationsPage`: list of notifications, "mark as read" button per item, "mark all as read" button.

Header notification bell shows unread count badge from this hook.

**Step 2: Write test**

```typescript
test('NotificationsPage shows unread notifications', () => {
  // Mock notifications response → verify list rendered
});
```

**Step 3: Commit**

```bash
git commit -m "feat: add Notifications page with 30s polling and backoff"
```

---

### Task 12: Admin Dashboard — Categories, Tags, Role Management

**Files:**
- Create: `frontend/src/pages/AdminDashboard.tsx`

**Step 1: Implement**

Tabs or sections:
- **Categories**: list, create, edit, delete
- **Tags**: list, create
- **Users**: search users, assign AUTHOR/USER role
- **Deleted Posts**: list soft-deleted posts, restore button

All behind `ProtectedRoute requiredRole="ADMIN"`.

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

### Task 13: Author Listing and Author Profile Pages

**Files:**
- Create: `frontend/src/pages/AuthorPage.tsx`

**Step 1: Implement**

Authors page: list all authors with post counts. Author detail page: biography, social links, paginated posts by that author.

**Step 2: Commit**

```bash
git commit -m "feat: add Author listing and profile pages"
```

---

### Task 14: Common Components — LoadingSpinner, ErrorMessage, ConfirmDialog

**Files:**
- Create: `frontend/src/components/common/LoadingSpinner.tsx`
- Create: `frontend/src/components/common/ErrorMessage.tsx`
- Create: `frontend/src/components/common/ConfirmDialog.tsx`

**Step 1: Implement**

Simple, reusable UI components. `ConfirmDialog` used for delete confirmations (posts, comments).

**Step 2: Commit**

```bash
git commit -m "feat: add common UI components"
```

---

### Task 15: Utility Functions

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

### Task 16: MSW Setup for Component Tests

**Files:**
- Create: `frontend/src/test/mocks/handlers.ts`
- Create: `frontend/src/test/mocks/server.ts`
- Modify: `frontend/src/test/setup.ts`

**Step 1: Set up MSW**

Mock API handlers for all endpoints used in tests. Setup MSW server in test setup file.

**Step 2: Verify all existing tests still pass with MSW**

Run: `cd frontend && npx vitest run`

**Step 3: Commit**

```bash
git commit -m "feat: add MSW mock server for component tests"
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
- Vite + React + TypeScript project with Tailwind CSS
- Vite proxy for API requests during development
- TypeScript types mirroring back-end DTOs
- Axios client with CSRF token handling and 401 redirect
- API service layer for all endpoints
- AuthContext + useAuth hook
- React Router v6 routing with protected routes
- Login/Register pages with form validation
- Home page with post feed, filters, pagination, search
- Post detail page with Markdown rendering (react-markdown + rehype-sanitize), comments, likes
- Post editor with Markdown textarea and live preview
- User profile and saved posts pages
- Notifications page with 30-second polling and exponential backoff
- Admin dashboard (categories, tags, users, deleted posts)
- Author listing and profile pages
- Common UI components and utility functions
- MSW mock server for component tests
- Cypress E2E tests for critical flows

**Next plan:** Phase 4 (Production Deployment — VPS)
