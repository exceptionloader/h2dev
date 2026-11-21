package dev.exceptionloader.reports.common.storage;

import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.common.storage.entity.ProfileModel;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filters;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.Blocking;

/**
 * Data access for the {@code profiles} collection.
 */
@Singleton
public class ProfileRepository {

    private final MongoConnector connector;

    @Inject
    public ProfileRepository(MongoConnector connector) {
        this.connector = connector;
    }

    @Blocking
    public void save(PlayerProfile profile, Instant seenAt) {
        ProfileModel model = new ProfileModel();
        model.setId(profile.uuid());
        model.setName(profile.name());
        model.setNameLower(profile.name().toLowerCase(Locale.ROOT));
        model.setEdition(profile.edition());
        model.setLastSeen(seenAt);
        datastore().save(model);
    }

    /**
     * @return the player that most recently used this name
     */
    @Blocking
    public Optional<PlayerProfile> findByName(String name) {
        ProfileModel model = datastore().find(ProfileModel.class)
                .filter(Filters.eq("nameLower", name.toLowerCase(Locale.ROOT)))
                .first(new FindOptions().sort(Sort.descending("lastSeen")));
        return Optional.ofNullable(model).map(ProfileModel::toProfile);
    }

    private Datastore datastore() {
        return connector.datastore();
    }
}
