# Critical Implementation Review — Phase 3: Front-End (React SPA)

**Reviewed Plan:** `docs/plans/phase3/2026-03-01-phase3-frontend-implementation.md`
**Review Version:** 1
**Date:** 2026-03-16

---

## 1. Overall Assessment

The plan is well-structured, follows the approved design document closely, and demonstrates solid choices (React Query for server state, rehype-sanitize for XSS protection, MSW for test mocking). The task ordering is mostly logical and the TDD approach for key components is commendable.

**However, there are several critical issues:** a type mismatch between the front-end `User` type and the back-end `AuthResponse` DTO, a 401 interceptor that will cause an infinite redirect loop, missing CSRF token handling nuances with Spring Security 6, a `useAuth` hook defined in two places simultaneously, and insufficient role-guarding for author-only routes. The plan also has task-ordering problems that will create compilation failures mid-implementation.

---

## 2. Critical Issues

### 2.1 Type Mismatch: Front-End `User` vs Back-End `AuthResponse`

**Description:** The plan defines `User` (Task 2) with fields like `accountId`, `username`, `email`, `role`, `isVip`, `emailVerified`. The back-end `AuthResponse` record has the same fields, but `role` is a plain `String`, not the union type `'ADMIN' | 'AUTHOR' | 'USER'`. More critically, `AuthContext` (Task 4) does `setUser(res.data.data)` after login, meaning it stores an `AuthResponse` into a `User` typed state. If the back-end ever returns a role string that doesn't match the union (e.g., `"ROLE_ADMIN"` with a prefix), the type system won't catch it at runtime and `ProtectedRoute` role checks will silently fail.

**Impact:** Silent authorization bypass — users may gain or lose access to protected routes depending on role string format.

**Fix:** Add a runtime validation/mapping layer between API responses and front-end types. Create a `mapAuthResponse(data: unknown): User` function that normalizes role strings and validates the shape. Add a unit test confirming the mapping handles the exact strings the back-end produces.

### 2.2 Infinite Redirect Loop on 401 Interceptor

**Description:** The global Axios 401 interceptor (Task 3) does `window.location.href = '/login'` on any 401. However, `AuthContext` (Task 4) calls `getCurrentUser()` on mount to check if the user has an active session. For unauthenticated users, this will return 401, triggering the interceptor, which redirects to `/login`. The login page renders `AuthProvider`, which calls `getCurrentUser()` again → 401 → redirect → infinite loop.

**Impact:** Application is completely unusable for unauthenticated users. This is a showstopper.

**Fix:** Either: (a) exclude the `/auth/me` endpoint from the 401 interceptor, or (b) have `getCurrentUser()` use a separate Axios instance without the 401 interceptor, or (c) replace the interceptor with a flag/callback pattern where `AuthContext` controls the redirect logic. Option (c) is cleanest — let the interceptor emit an event or call a callback rather than directly redirecting, and let `AuthContext` decide whether to redirect based on the current route.

### 2.3 CSRF Token Handling Incomplete for Spring Security 6

**Description:** The plan reads `XSRF-TOKEN` from `document.cookie` and sends it as `X-XSRF-TOKEN`. Spring Security 6 with `CookieCsrfTokenRepository.withHttpOnlyFalse()` sets the cookie name as `XSRF-TOKEN` by default, which matches. However, Spring Security 6 introduced **deferred CSRF token loading** — the CSRF token is not set in the cookie until the first request that triggers token generation. If the SPA loads and the user's first action is a POST (e.g., login), the cookie won't exist yet.

**Impact:** Login and registration will fail with 403 Forbidden for new sessions where no GET request has been made first.

**Fix:** Add a `client.get('/auth/csrf')` call (or any GET endpoint) during app initialization to ensure the CSRF cookie is set before any mutation. Alternatively, configure the back-end's `CsrfTokenRequestAttributeHandler` to eagerly load the token. The plan should explicitly document which approach is used. Also note: the back-end `SecurityConfig` sets the request attribute name to `null` via `CsrfTokenRequestAttributeHandler`, which is a Spring Security 6 pattern — confirm the cookie name hasn't been customized.

### 2.4 `useAuth` Hook Defined in Two Places

**Description:** In Task 4, the `useAuth` function is defined inside `AuthContext.tsx` (lines 373-377) AND a separate file `frontend/src/hooks/useAuth.ts` is listed in the files to create. This will cause either a duplicate export conflict or one file silently shadowing the other depending on import paths.

**Impact:** Confusing imports, potential for components to import the wrong `useAuth` and get `undefined` context.

**Fix:** Define `useAuth` only in `AuthContext.tsx` and re-export it from `hooks/useAuth.ts` for convenience, or define it only in `hooks/useAuth.ts` and import `AuthContext` there. Pick one canonical location.

### 2.5 Author-Only Routes Not Role-Guarded

**Description:** `ProtectedRoute` (Task 5) checks for authentication and optionally for `requiredRole="ADMIN"`. However, the create/edit post routes are wrapped in a generic `<ProtectedRoute />` without role restriction. Any authenticated user (including `USER` role) can navigate to `/create-post` and `/edit-post/:id`. The back-end likely rejects the API call, but the user still sees the editor form and gets a confusing error only on submit.

**Impact:** Poor UX and potential confusion. Users see functionality they can't use. If the back-end has a bug in role checking, this becomes a security issue.

**Fix:** Either: (a) add `requiredRole="AUTHOR"` to the create/edit post routes (and update `ProtectedRoute` to accept an array of allowed roles, since ADMINs should also be able to create posts), or (b) conditionally render the "Create Post" link in the Header and add a role check at the page component level with a proper "Access Denied" message.

### 2.6 Task Ordering Causes Compilation Failures

**Description:** Task 14 (common components: LoadingSpinner, ErrorMessage, ConfirmDialog) and Task 16 (MSW setup) are defined late, but Tasks 4-12 will almost certainly import these components and need MSW for tests. For example, any page with loading/error states needs `LoadingSpinner` and `ErrorMessage`. The tests in Tasks 4-9 need MSW to mock API calls.

**Impact:** Tasks 4-12 will fail to compile or tests will fail until Tasks 14 and 16 are completed, breaking the incremental build approach.

**Fix:** Move Task 14 (common components) to immediately after Task 2 (types). Move Task 16 (MSW setup) to immediately after Task 3 (API layer), before Task 4 (AuthContext), since the AuthContext test needs to mock `getCurrentUser()`.

### 2.7 No Error Boundary

**Description:** The plan has no React Error Boundary anywhere in the component tree. If any component throws during rendering (e.g., malformed API data, undefined property access), the entire app white-screens with no recovery path.

**Impact:** Any runtime error in any component crashes the entire application with no user feedback.

**Fix:** Add an `ErrorBoundary` component wrapping the route outlet in `Layout.tsx`. It should catch render errors, display a user-friendly fallback, and include a "Try Again" button that resets the error state. This is standard production practice for React apps.

---

## 3. Minor Issues & Improvements

### 3.1 Test Uses `jest.fn()` Instead of `vi.fn()`

Task 9's test code uses `jest.fn()`, but the project uses Vitest, not Jest. Should be `vi.fn()`. This will cause a `ReferenceError` at test runtime.

### 3.2 Missing `AuthorsPage` in Route Definition

Task 5 routes reference `<AuthorsPage />` at `/authors`, but no task explicitly creates an `AuthorsPage` component. Task 13 creates `AuthorPage.tsx` (singular) for the detail view. The authors listing page needs its own file (`AuthorsPage.tsx`), or Task 13 should explicitly create both.

### 3.3 No Optimistic Updates for Likes/Saves

The like and save toggle actions go through a standard mutation → refetch cycle. For frequent interactions like likes, this creates a visible delay. React Query's `useMutation` with `onMutate` optimistic update would provide instant UI feedback with automatic rollback on failure.

### 3.4 Notification Polling Continues When Tab Is Hidden

The 30-second polling interval runs regardless of tab visibility. This wastes bandwidth and battery for background tabs. React Query supports `refetchIntervalInBackground: false` (set by default in newer versions, but should be explicit).

### 3.5 No `QueryClient` Configuration Shown

Task 5 uses `<QueryClientProvider client={queryClient}>` but never defines `queryClient` with default options. Production apps should configure `staleTime`, `retry`, and `refetchOnWindowFocus` defaults. Without `staleTime`, React Query refetches on every component mount, causing unnecessary API calls.

### 3.6 Recursive Comment Rendering Has No Depth Limit

`CommentItem` renders `replies: Comment[]` recursively. Deeply nested threads (10+ levels) will create excessive DOM nesting and potentially overflow the viewport with indentation. Add a max depth (e.g., 5 levels), after which replies are flattened or a "Continue thread" link is shown.

### 3.7 No Debouncing on Search/Filter Inputs

`PostFilters` (Task 7) triggers a new fetch on every filter change. If the search input triggers on each keystroke, this creates excessive API calls. Add debouncing (300-500ms) on text input filters.

### 3.8 No Accessibility Considerations

The plan mentions no ARIA attributes, keyboard navigation, focus management after route changes, or screen reader support. At minimum: form inputs need labels (not just placeholders), the notification bell needs `aria-label` with count, and route changes should announce to screen readers.

### 3.9 Tailwind CSS v4 Setup Discrepancy

The plan instructs adding `@tailwind base; @tailwind components; @tailwind utilities;` directives. If using Tailwind CSS v4 (current as of 2026), these directives have been replaced with `@import "tailwindcss"`. The plan should specify which Tailwind version to install and use the corresponding setup.

### 3.10 `PostDetail` Missing Read-Tracking Trigger

The `PostDetail` component renders post content and the `CommentForm` checks `hasRead` to enable commenting. But no code in the plan triggers the "mark as read" API call. The user reads the post but `hasRead` stays `false` unless something calls the read-tracking endpoint. Add a `useEffect` in `PostPage` or `PostDetail` that calls `postsApi.markAsRead(postId)` on mount.

### 3.11 No Production Build or Environment Configuration

The plan covers development (Vite proxy) but doesn't address production build configuration — how API base URLs are set in production, environment variables (`VITE_API_URL`), or the build output integration with Nginx/VPS. This is deferred to Phase 4, but the Axios client's `baseURL: '/api/v1'` hardcoding should be noted as production-ready only if a reverse proxy is guaranteed.

---

## 4. Questions for Clarification

1. **Does the back-end's `AuthResponse.role` return `"ADMIN"` or `"ROLE_ADMIN"`?** The front-end union type assumes bare role names. If Spring Security's default `ROLE_` prefix is used, every role check will fail silently.

2. **Is there a dedicated `/auth/me` or `/auth/csrf` GET endpoint?** The plan references `getCurrentUser()` calling `/auth/me`, but this endpoint isn't listed in the back-end's public endpoints in `SecurityConfig`. If it requires authentication, the initial session check will always 401 for new users — compounding issue 2.2.

3. **What does the back-end return for paginated post listings?** The front-end `PostSummary` type includes `commentCount`, but it's unclear if the back-end's post listing DTO includes this field or if it requires a separate query. This could introduce an N+1 problem if the back-end computes it per-post.

4. **Are there plans for image uploads?** The design doc shows `BlogPost ──1:N──> Image`, but the post editor (`PostForm`) has no image upload UI. Is this intentionally deferred?

5. **Should `ProtectedRoute` show a loading spinner while `AuthContext.isLoading` is true?** Currently, if auth state is loading, the route may briefly flash the login redirect before the session check completes.

---

## 5. Final Recommendation

**Major revisions needed.**

The plan's structure and technology choices are sound, but three issues are showstoppers that must be resolved before implementation:

1. **Fix the 401 infinite redirect loop** (Issue 2.2) — the app will be completely non-functional without this.
2. **Fix the CSRF token initialization** (Issue 2.3) — login/register will fail for new sessions.
3. **Reorder tasks** (Issue 2.6) — the incremental build approach will break without moving common components and MSW earlier.

Additionally, the type mismatch (2.1), duplicate `useAuth` definition (2.4), and missing author role guard (2.5) should be addressed before implementation begins. The error boundary (2.7) and read-tracking trigger (3.10) should be added as explicit tasks.
