package dev.exceptionloader.reports.common.storage.entity;

import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.api.model.ReportStatus;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import dev.morphia.utils.IndexType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;
import org.bson.types.ObjectId;

/**
 * Document of the {@code reports} collection.
 *
 * <p>{@code reporterNotified} is {@code null} while a report is open. Closing a report sets it to
 * {@code false}, delivering the result to the reporter flips it to {@code true}. The partial index
 * on that field only contains undelivered results, which keeps the login check cheap.
 */
@Entity(value = "reports", useDiscriminator = false)
@Indexes({
        @Index(fields = @Field("status")),
        @Index(fields = @Field("reported")),
        @Index(fields = @Field("reporter")),
        @Index(fields = @Field(value = "createdAt", type = IndexType.DESC)),
        @Index(fields = @Field("server")),
        @Index(fields = {@Field("status"), @Field(value = "createdAt", type = IndexType.DESC)},
                options = @IndexOptions(name = "status_createdAt")),
        @Index(fields = {@Field("reporter"), @Field("handledAt")},
                options = @IndexOptions(name = "pending_notifications", partialFilter = "{ reporterNotified: false }"))
})
@Getter
@Setter
@NoArgsConstructor
@FieldNameConstants
public class ReportModel {

    @Id
    private ObjectId id;
    private UUID reporter;
    private UUID reported;
    private String reporterName;
    private String reportedName;
    private String template;
    private String reason;
    private ReportStatus status;
    private UUID handledBy;
    private String handledByName;
    private String modNote;
    private String server;
    private Instant createdAt;
    private Instant handledAt;
    private Boolean reporterNotified;

    public Report toReport() {
        return new Report(
                id.toHexString(),
                reporter,
                reporterName,
                reported,
                reportedName,
                Objects.requireNonNullElse(template, "other"),
                reason,
                status,
                handledBy,
                handledByName,
                modNote,
                server,
                createdAt,
                handledAt);
    }
}
