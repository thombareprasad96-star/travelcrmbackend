package com.crm.travelcrm.platform.config.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Platform-wide key/value setting (e.g. {@code maintenance.enabled}). Platform-level — extends
 * {@link BaseEntity}, NOT tenant-scoped, so it is never caught by the tenant {@code @Filter}.
 */
@Entity
@Table(name = "platform_config",
        indexes = @Index(name = "idx_platform_config_key", columnList = "config_key"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformConfig extends BaseEntity {

    @Column(name = "config_key", nullable = false, unique = true, length = 120)
    private String key;

    @Column(name = "config_value", columnDefinition = "text")
    private String value;

    @Column(name = "description", length = 255)
    private String description;
}