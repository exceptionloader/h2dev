package dev.exceptionloader.reports.common.storage;

import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Facet;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import dev.exceptionloader.reports.api.action.ReportDecision;
import dev.exceptionloader.reports.api.model.PlayerStatistics;
import dev.exceptionloader.reports.api.model.ReportStatistics;
import dev.exceptionloader.reports.api.model.ReportStatus;
import dev.exceptionloader.reports.api.model.StatusCounts;
import dev.exceptionloader.reports.common.storage.entity.ReportModel;
import dev.exceptionloader.reports.common.storage.entity.ReportModel.Fields;
import dev.morphia.Datastore;
import dev.morphia.ModifyOptions;
import dev.morphia.UpdateOptions;
import dev.morphia.query.CountOptions;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Query;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperator;
import dev.morphia.query.updates.UpdateOperators;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.Blocking;

/**
 * Data access for the {@code reports} collection. All methods block and must run off the main
 * thread; the services take care of that.
 */
@Singleton
public class ReportRepository {

    private static final String COUNT = "count";

    private final MongoConnector connector;

    @Inject
    public ReportRepository(MongoConnector connector) {
        this.connector = connector;
    }

    @Blocking
    public void insert(ReportModel report) {
        datastore().insert(report);
    }

    @Blocking
    public Optional<ReportModel> findById(ObjectId id) {
        return Optional.ofNullable(query(Filters.eq("_id", id)).first());
    }

    @Blocking
    public Optional<ReportModel> findOpen(UUID reporter, UUID reported) {
        return Optional.ofNullable(query(
                Filters.eq(Fields.reporter, reporter),
                Filters.eq(Fields.reported, reported),
                Filters.eq(Fields.status, ReportStatus.OPEN)).first());
    }

    @Blocking
    public long countOpenBy(UUID reporter) {
        return query(Filters.eq(Fields.reporter, reporter), Filters.eq(Fields.status, ReportStatus.OPEN))
                .count(new CountOptions());
    }

    @Blocking
    public long countByStatus(ReportStatus status) {
        return query(Filters.eq(Fields.status, status)).count(new CountOptions());
    }

    @Blocking
    public long count(Filter[] filters) {
        return query(filters).count(new CountOptions());
    }

    @Blocking
    public List<ReportModel> find(Filter[] filters, Sort[] sort, int offset, int limit) {
        FindOptions options = new FindOptions().sort(sort).skip(offset).limit(limit);
        try (var cursor = query(filters).iterator(options)) {
            return cursor.toList();
        }
    }

    /**
     * Atomically closes a report if it is still open. A moderator can never close a report filed
     * against themselves.
     *
     * @return the updated report, empty if it was not open (or not closable by this moderator)
     */
    @Blocking
    public Optional<ReportModel> close(ObjectId id, ReportDecision decision, Instant handledAt) {
        List<Filter> filters = new ArrayList<>(3);
        filters.add(Filters.eq("_id", id));
        filters.add(Filters.eq(Fields.status, ReportStatus.OPEN));
        if (decision.moderator() != null) {
            filters.add(Filters.ne(Fields.reported, decision.moderator()));
        }

        List<UpdateOperator> updates = new ArrayList<>(6);
        updates.add(UpdateOperators.set(Fields.status, decision.outcome()));
        updates.add(UpdateOperators.set(Fields.handledByName, decision.moderatorName()));
        updates.add(UpdateOperators.set(Fields.handledAt, handledAt));
        updates.add(UpdateOperators.set(Fields.reporterNotified, false));
        if (decision.moderator() != null) {
            updates.add(UpdateOperators.set(Fields.handledBy, decision.moderator()));
        }
        if (decision.note() != null) {
            updates.add(UpdateOperators.set(Fields.modNote, decision.note()));
        }

        return Optional.ofNullable(query(filters.toArray(Filter[]::new))
                .modify(new ModifyOptions().returnDocument(ReturnDocument.AFTER), updates.toArray(UpdateOperator[]::new)));
    }

    /**
     * Marks the result of a report as delivered. Only the first caller gets the document back,
     * which is what prevents duplicate notifications across instances.
     */
    @Blocking
    public Optional<ReportModel> claimNotification(ObjectId id) {
        return Optional.ofNullable(query(Filters.eq("_id", id), Filters.eq(Fields.reporterNotified, false))
                .modify(new ModifyOptions().returnDocument(ReturnDocument.AFTER),
                        UpdateOperators.set(Fields.reporterNotified, true)));
    }

    /**
     * Claims the oldest undelivered result of a reporter.
     */
    @Blocking
    public Optional<ReportModel> claimNextPending(UUID reporter) {
        ModifyOptions options = new ModifyOptions()
                .returnDocument(ReturnDocument.AFTER)
                .sort(Sorts.ascending(Fields.handledAt));
        return Optional.ofNullable(query(Filters.eq(Fields.reporter, reporter), Filters.eq(Fields.reporterNotified, false))
                .modify(options, UpdateOperators.set(Fields.reporterNotified, true)));
    }

    /**
     * Hands a claimed notification back, e.g. because the reporter disconnected in the meantime.
     */
    @Blocking
    public void releaseNotification(ObjectId id) {
        query(Filters.eq("_id", id)).update(new UpdateOptions(), UpdateOperators.set(Fields.reporterNotified, false));
    }

    @Blocking
    public List<String> distinctServers() {
        return datastore().getCollection(ReportModel.class)
                .distinct(Fields.server, String.class)
                .into(new ArrayList<>());
    }

    /**
     * Counts reports per status and the most used templates in a single round trip.
     */
    @Blocking
    public ReportStatistics statistics(int topTemplates) {
        Document result = aggregate(List.of(Aggregates.facet(
                new Facet("status", countBy(Fields.status)),
                new Facet("templates",
                        countBy(Fields.template),
                        Aggregates.sort(Sorts.descending(COUNT)),
                        Aggregates.limit(topTemplates)))));

        Map<String, Long> templates = new LinkedHashMap<>();
        for (Document entry : result.getList("templates", Document.class, List.of())) {
            templates.put(String.valueOf(entry.get("_id")), count(entry));
        }
        return new ReportStatistics(statusCounts(result.getList("status", Document.class, List.of())), templates);
    }

    /**
     * Counts reports received and filed by a player. The leading match uses the reporter and
     * reported indexes, the facets then split the (small) result set.
     */
    @Blocking
    public PlayerStatistics statistics(UUID player) {
        Bson received = com.mongodb.client.model.Filters.eq(Fields.reported, player);
        Bson filed = com.mongodb.client.model.Filters.eq(Fields.reporter, player);
        Document result = aggregate(List.of(
                Aggregates.match(com.mongodb.client.model.Filters.or(received, filed)),
                Aggregates.facet(
                        new Facet("received", Aggregates.match(received), countBy(Fields.status)),
                        new Facet("filed", Aggregates.match(filed), countBy(Fields.status)))));

        return new PlayerStatistics(
                player,
                statusCounts(result.getList("received", Document.class, List.of())),
                statusCounts(result.getList("filed", Document.class, List.of())));
    }

    private Document aggregate(List<Bson> pipeline) {
        Document result = datastore().getCollection(ReportModel.class)
                .withDocumentClass(Document.class)
                .aggregate(pipeline)
                .first();
        return result == null ? new Document() : result;
    }

    private static Bson countBy(String field) {
        return Aggregates.group("$" + field, Accumulators.sum(COUNT, 1));
    }

    private static StatusCounts statusCounts(List<Document> groups) {
        Map<ReportStatus, Long> counts = new EnumMap<>(ReportStatus.class);
        for (Document group : groups) {
            Object status = group.get("_id");
            if (status != null) {
                try {
                    counts.put(ReportStatus.valueOf(status.toString()), count(group));
                } catch (IllegalArgumentException ignored) {
                    // unknown status written by a newer version
                }
            }
        }
        return new StatusCounts(
                counts.getOrDefault(ReportStatus.OPEN, 0L),
                counts.getOrDefault(ReportStatus.RESOLVED, 0L),
                counts.getOrDefault(ReportStatus.REJECTED, 0L));
    }

    private static long count(Document group) {
        return group.get(COUNT) instanceof Number number ? number.longValue() : 0;
    }

    private Query<ReportModel> query(Filter... filters) {
        return datastore().find(ReportModel.class).filter(filters);
    }

    private Datastore datastore() {
        return connector.datastore();
    }
}
