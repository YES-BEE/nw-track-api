package com.yesbee.nwtrack.storage;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class StorageTokenPolicy {
    public String normalize(boolean enabled, String token) {
        if (!enabled) return null;
        if (token == null || token.isBlank()) throw new BusinessRuleException("STORAGE_TOKEN_REQUIRED", "Storage token is required for this factory");
        String normalized = token.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 50) throw new BusinessRuleException("STORAGE_TOKEN_INVALID", "Storage token must not exceed 50 characters");
        return normalized;
    }
}
