package com.blogplatform.author;

import com.blogplatform.user.UserAccount;
import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "author_profile")
public class AuthorProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "author_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private UserAccount account;

    @Size(max = 255)
    @Column(name = "biography")
    private String biography;

    // SECURITY: When the author profile update endpoint is built (Phase 2),
    // the DTO/controller MUST validate: map size ≤ 10, keys from allowlist
    // (twitter, github, linkedin, website), values match ^https?://.*
    // to prevent stored XSS via javascript: or data: URI schemes.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "social_links", columnDefinition = "jsonb")
    private Map<String, String> socialLinks;

    @Size(max = 255)
    @Column(name = "expertise", length = 255)
    private String expertise;

    public AuthorProfile() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UserAccount getAccount() { return account; }
    public void setAccount(UserAccount account) { this.account = account; }
    public String getBiography() { return biography; }
    public void setBiography(String biography) { this.biography = biography; }
    public Map<String, String> getSocialLinks() { return socialLinks; }
    public void setSocialLinks(Map<String, String> socialLinks) { this.socialLinks = socialLinks; }
    public String getExpertise() { return expertise; }
    public void setExpertise(String expertise) { this.expertise = expertise; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuthorProfile that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
