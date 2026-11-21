package dev.exceptionloader.reports.common.storage;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.exceptionloader.reports.common.config.MongoSettings;
import dev.exceptionloader.reports.common.storage.entity.ReportModel;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import dev.morphia.config.MorphiaConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.jetbrains.annotations.Blocking;

/**
 * Owns the MongoDB client and the Morphia datastore.
 *
 * <p>The driver connects lazily and reconnects on its own, so creating this object never blocks.
 * The datastore is created on first use from an async task, because Morphia creates the indexes
 * declared on the entities at that point.
 */
@Singleton
public class MongoConnector implements AutoCloseable {

    private final MongoSettings settings;
    private final MongoClient client;
    private volatile Datastore datastore;

    @Inject
    public MongoConnector(MongoSettings settings) {
        this.settings = settings;
        MongoClientSettings clientSettings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(settings.uri()))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .applicationName("report-system")
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(socket -> socket
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS))
                .build();
        this.client = MongoClients.create(clientSettings);
    }

    /**
     * @return the datastore, created together with the indexes on the first call
     */
    @Blocking
    public Datastore datastore() {
        Datastore current = datastore;
        if (current == null) {
            synchronized (this) {
                current = datastore;
                if (current == null) {
                    current = withOwnClassLoader(this::createDatastore);
                    datastore = current;
                }
            }
        }
        return current;
    }

    @Blocking
    public void ping() {
        client.getDatabase("admin").runCommand(new Document("ping", 1));
    }

    @Override
    public void close() {
        client.close();
    }

    private Datastore createDatastore() {
        MorphiaConfig config = MorphiaConfig.load()
                .database(settings.database())
                .packages(List.of(ReportModel.class.getPackageName()))
                .applyIndexes(true)
                .uuidRepresentation(UuidRepresentation.STANDARD);
        return Morphia.createDatastore(client, config);
    }

    /*
     * Morphia scans the entity package and reads its configuration through the context class
     * loader, which on Bukkit and BungeeCord belongs to the server and cannot see this plugin.
     */
    private <T> T withOwnClassLoader(Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(getClass().getClassLoader());
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
