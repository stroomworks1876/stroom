package stroom.proxy.app.event;

import stroom.util.cache.CacheConfig;
import stroom.util.config.annotations.RequiresProxyRestart;
import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsProxyConfig;
import stroom.util.time.StroomDuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

@Singleton
@JsonPropertyOrder(alphabetic = true)
public class EventStoreConfig extends AbstractConfig implements IsProxyConfig {

    @JsonProperty
    private final StroomDuration rollFrequency;
    @JsonProperty
    private final StroomDuration maxAge;
    @JsonProperty
    private final long maxEventCount;
    @JsonProperty
    private final long maxByteCount;
    @JsonProperty
    private final CacheConfig openFilesCache;
    @JsonProperty
    private final int forwardQueueSize;

    public EventStoreConfig() {
        rollFrequency = StroomDuration.ofSeconds(10);
        maxAge = StroomDuration.ofMinutes(1);
        maxEventCount = Long.MAX_VALUE;
        maxByteCount = Long.MAX_VALUE;
        openFilesCache = CacheConfig.builder()
                .maximumSize(100)
                .statisticsMode(CacheConfig.PROXY_DEFAULT_STATISTICS_MODE)
                .build();
        forwardQueueSize = 1_000;
    }

    @SuppressWarnings("unused")
    @JsonCreator
    public EventStoreConfig(@JsonProperty("rollFrequency") final StroomDuration rollFrequency,
                            @JsonProperty("maxAge") final StroomDuration maxAge,
                            @JsonProperty("maxEventCount") final long maxEventCount,
                            @JsonProperty("maxByteCount") final long maxByteCount,
                            @JsonProperty("openFilesCache") final CacheConfig openFilesCache,
                            @JsonProperty("forwardQueueSize") final int forwardQueueSize) {
        this.rollFrequency = rollFrequency;
        this.maxAge = maxAge;
        this.maxEventCount = maxEventCount;
        this.maxByteCount = maxByteCount;
        this.openFilesCache = openFilesCache;
        this.forwardQueueSize = forwardQueueSize;
    }

    @NotNull
    public StroomDuration getRollFrequency() {
        return rollFrequency;
    }

    @NotNull
    public StroomDuration getMaxAge() {
        return maxAge;
    }

    @Min(0)
    public long getMaxEventCount() {
        return maxEventCount;
    }

    @Min(0)
    public long getMaxByteCount() {
        return maxByteCount;
    }

    @RequiresProxyRestart
    public CacheConfig getOpenFilesCache() {
        return openFilesCache;
    }

    @RequiresProxyRestart
    @Min(0)
    public int getForwardQueueSize() {
        return forwardQueueSize;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final EventStoreConfig that = (EventStoreConfig) o;
        return maxEventCount == that.maxEventCount
               && maxByteCount == that.maxByteCount
               && openFilesCache == that.openFilesCache
               && forwardQueueSize == that.forwardQueueSize
               && Objects.equals(rollFrequency, that.rollFrequency)
               && Objects.equals(maxAge, that.maxAge);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rollFrequency, maxAge, maxEventCount, maxByteCount, openFilesCache, forwardQueueSize);
    }

    @Override
    public String toString() {
        return "EventStoreConfig{" +
               "rollFrequency=" + rollFrequency +
               ", maxAge=" + maxAge +
               ", maxEventCount=" + maxEventCount +
               ", maxByteCount=" + maxByteCount +
               ", openFilesCache=" + openFilesCache +
               ", forwardQueueSize=" + forwardQueueSize +
               '}';
    }
}
