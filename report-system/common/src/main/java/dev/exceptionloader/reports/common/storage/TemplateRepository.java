package dev.exceptionloader.reports.common.storage;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoWriteException;
import dev.exceptionloader.reports.common.storage.entity.TemplateModel;
import dev.morphia.Datastore;
import dev.morphia.InsertManyOptions;
import dev.morphia.query.CountOptions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import org.jetbrains.annotations.Blocking;

/**
 * Data access for the {@code templates} collection.
 */
@Singleton
public class TemplateRepository {

    private final MongoConnector connector;

    @Inject
    public TemplateRepository(MongoConnector connector) {
        this.connector = connector;
    }

    @Blocking
    public List<TemplateModel> findAll() {
        try (var cursor = datastore().find(TemplateModel.class).iterator()) {
            return cursor.toList();
        }
    }

    @Blocking
    public boolean isEmpty() {
        return datastore().find(TemplateModel.class).count(new CountOptions().limit(1)) == 0;
    }

    /**
     * Inserts the given templates, ignoring ids that already exist. Several instances may run this
     * at the same time on a fresh database.
     */
    @Blocking
    public void insertMissing(List<TemplateModel> templates) {
        try {
            datastore().insert(templates, new InsertManyOptions().ordered(false));
        } catch (MongoBulkWriteException exception) {
            boolean onlyDuplicates = exception.getWriteErrors().stream()
                    .allMatch(error -> ErrorCategory.fromErrorCode(error.getCode()) == ErrorCategory.DUPLICATE_KEY);
            if (!onlyDuplicates) {
                throw exception;
            }
        } catch (MongoWriteException exception) {
            if (exception.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw exception;
            }
        }
    }

    private Datastore datastore() {
        return connector.datastore();
    }
}
