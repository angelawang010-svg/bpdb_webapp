package com.blogplatform.common.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("ownershipVerifier")
public class OwnershipVerifier {

    public boolean isOwnerOrAdmin(Long resourceOwnerAccountId, Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return false;
        }
        Long currentAccountId = Long.valueOf(authentication.getName());
        if (currentAccountId.equals(resourceOwnerAccountId)) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    public void verify(Long resourceOwnerAccountId, Authentication authentication) {
        if (!isOwnerOrAdmin(resourceOwnerAccountId, authentication)) {
            throw new AccessDeniedException("You do not have permission to access this resource");
        }
    }
}
