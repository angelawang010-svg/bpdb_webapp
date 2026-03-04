-- UserAccount
CREATE TABLE user_account (
    account_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'AUTHOR', 'ADMIN')),
    is_vip BOOLEAN NOT NULL DEFAULT FALSE,
    vip_start_date TIMESTAMPTZ,
    vip_end_date TIMESTAMPTZ,
    two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- UserProfile
CREATE TABLE user_profile (
    profile_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES user_account(account_id),
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    bio TEXT,
    profile_pic_url VARCHAR(500),
    last_login TIMESTAMPTZ,
    login_count INTEGER NOT NULL DEFAULT 0
);

-- AuthorProfile
CREATE TABLE author_profile (
    author_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES user_account(account_id),
    biography TEXT,
    social_links JSONB,
    expertise VARCHAR(255)
);

-- Category
CREATE TABLE category (
    category_id BIGSERIAL PRIMARY KEY,
    category_name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT
);

-- Tag
CREATE TABLE tag (
    tag_id BIGSERIAL PRIMARY KEY,
    tag_name VARCHAR(50) NOT NULL UNIQUE
);

-- BlogPost
-- Note: author_id references user_account(account_id), not author_profile.
-- Any user can author posts; an author_profile is optional supplemental data.
CREATE TABLE blog_post (
    post_id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    author_id BIGINT NOT NULL REFERENCES user_account(account_id),
    category_id BIGINT REFERENCES category(category_id),
    is_premium BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    search_vector TSVECTOR,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_blog_post_author ON blog_post(author_id);
CREATE INDEX idx_blog_post_category ON blog_post(category_id);
CREATE INDEX idx_blog_post_search_vector ON blog_post USING GIN(search_vector);

-- PostTags (join table)
CREATE TABLE post_tags (
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tag(tag_id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, tag_id)
);

-- PostUpdateLog
CREATE TABLE post_update_log (
    log_id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id) ON DELETE CASCADE,
    old_title VARCHAR(255),
    new_title VARCHAR(255),
    old_content TEXT,
    new_content TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_post_update_log_post ON post_update_log(post_id);

-- Comment
CREATE TABLE comment (
    comment_id BIGSERIAL PRIMARY KEY,
    content VARCHAR(250) NOT NULL,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    parent_comment_id BIGINT REFERENCES comment(comment_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_comment_post ON comment(post_id);
CREATE INDEX idx_comment_parent ON comment(parent_comment_id);

-- Like
CREATE TABLE post_like (
    like_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (account_id, post_id)
);

CREATE INDEX idx_like_post ON post_like(post_id);

-- ReadPost (join table)
CREATE TABLE read_post (
    account_id BIGINT NOT NULL REFERENCES user_account(account_id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, post_id)
);

-- SavedPost (join table)
CREATE TABLE saved_post (
    account_id BIGINT NOT NULL REFERENCES user_account(account_id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id) ON DELETE CASCADE,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, post_id)
);

-- Subscriber
CREATE TABLE subscriber (
    subscriber_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES user_account(account_id),
    subscribed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expiration_date TIMESTAMPTZ
);

-- Payment
CREATE TABLE payment (
    payment_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    payment_method VARCHAR(20) NOT NULL,
    transaction_id VARCHAR(255) NOT NULL UNIQUE,
    payment_date TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_account ON payment(account_id);

-- Notification
CREATE TABLE notification (
    notification_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    message TEXT NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_account_read_created ON notification(account_id, is_read, created_at);

-- Image
CREATE TABLE image (
    image_id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES blog_post(post_id),
    image_url VARCHAR(500) NOT NULL,
    alt_text VARCHAR(255),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_image_post ON image(post_id);

-- PasswordResetToken
CREATE TABLE password_reset_token (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE
);

-- EmailVerificationToken
CREATE TABLE email_verification_token (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    account_id BIGINT NOT NULL REFERENCES user_account(account_id),
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE
);
