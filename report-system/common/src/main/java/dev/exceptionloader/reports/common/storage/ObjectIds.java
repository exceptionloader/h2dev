package dev.exceptionloader.reports.common.storage;

import java.util.Optional;
import org.bson.types.ObjectId;

public final class ObjectIds {

    private ObjectIds() {
    }

    public static Optional<ObjectId> parse(String hex) {
        return hex != null && ObjectId.isValid(hex) ? Optional.of(new ObjectId(hex)) : Optional.empty();
    }
}
