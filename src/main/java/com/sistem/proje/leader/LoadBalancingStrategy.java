package com.sistem.proje.leader;

/**
 * Load balancing stratejileri
 */
public enum LoadBalancingStrategy {
    /**
     * Round-robin: Üyeler sırayla seçilir
     */
    ROUND_ROBIN,
    
    /**
     * Hash-based: message_id % member_count ile deterministik seçim
     */
    HASH_BASED
}

