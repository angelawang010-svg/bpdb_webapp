package com.blogplatform.post;

import java.io.Serializable;
import java.util.Objects;

public class SavedPostId implements Serializable {

    private Long account;
    private Long post;

    public SavedPostId() {}

    public SavedPostId(Long account, Long post) {
        this.account = account;
        this.post = post;
    }

    public Long getAccount() { return account; }
    public void setAccount(Long account) { this.account = account; }
    public Long getPost() { return post; }
    public void setPost(Long post) { this.post = post; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SavedPostId that)) return false;
        return Objects.equals(account, that.account) && Objects.equals(post, that.post);
    }

    @Override
    public int hashCode() {
        return Objects.hash(account, post);
    }
}
