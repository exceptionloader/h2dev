package dev.exceptionloader.reports.common.storage.entity;

import dev.exceptionloader.reports.api.model.Edition;
import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.Indexes;
import dev.morphia.utils.IndexType;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Document of the {@code profiles} collection: the last known name of every player that joined the
 * network. It serves as fallback when mc-api.io is unreachable.
 */
@Entity(value = "profiles", useDiscriminator = false)
@Indexes(@Index(fields = {@Field("nameLower"), @Field(value = "lastSeen", type = IndexType.DESC)}))
@Getter
@Setter
@NoArgsConstructor
public class ProfileModel {

    @Id
    private UUID id;
    private String name;
    private String nameLower;
    private Edition edition;
    private Instant lastSeen;

    public PlayerProfile toProfile() {
        return new PlayerProfile(id, name, edition == null ? Edition.JAVA : edition);
    }
}
