package com.blogplatform.post;

import com.blogplatform.user.UserAccount;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "saved_post")
@IdClass(SavedPostId.class)
public class SavedPost {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private UserAccount account;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private BlogPost post;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    public SavedPost() {}

    public UserAccount getAccount() { return account; }
    public void setAccount(UserAccount account) { this.account = account; }
    public BlogPost getPost() { return post; }
    public void setPost(BlogPost post) { this.post = post; }
    public Instant getSavedAt() { return savedAt; }
    public void setSavedAt(Instant savedAt) { this.savedAt = savedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SavedPost that)) return false;
        return account != null && post != null
                && Objects.equals(account.getAccountId(), that.account != null ? that.account.getAccountId() : null)
                && Objects.equals(post.getId(), that.post != null ? that.post.getId() : null);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                account != null ? account.getAccountId() : null,
                post != null ? post.getId() : null);
    }
}
