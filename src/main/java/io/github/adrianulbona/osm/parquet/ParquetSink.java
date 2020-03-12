package io.github.adrianulbona.osm.parquet;

import org.apache.parquet.hadoop.ParquetWriter;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Entity;
import org.openstreetmap.osmosis.core.domain.v0_6.EntityType;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static java.lang.String.format;


public class ParquetSink<T extends Entity> implements Sink {

    private final Path source;
    private final Path destinationFolder;
    private final boolean excludeMetadata;
    private final EntityType entityType;
    private final List<Predicate<T>> filters;
    private final int partitions;

    private AtomicLong idx = new AtomicLong(0L);
    private List<ParquetWriter<T>> writers;

    public ParquetSink(Path source, Path destinationFolder, boolean excludeMetadata, EntityType entityType, int partitions) {
        this.source = source;
        this.destinationFolder = destinationFolder;
        this.excludeMetadata = excludeMetadata;
        this.entityType = entityType;
        this.partitions = partitions;
        this.filters = new ArrayList<>();
        this.writers = new ArrayList<>();
    }

    @Override
    public void initialize(Map<String, Object> metaData) {
        final String pbfName = source.getFileName().toString();
        final String entityName = entityType.name().toLowerCase();

        try {
            for (int i = 0; i < partitions; ++i) {
                final Path destination = destinationFolder.resolve(format("%s/%s-part%d.parquet", entityName, pbfName, i));
                ParquetWriter<T> writer = ParquetWriterFactory.buildFor(destination.toAbsolutePath().toString(), excludeMetadata, entityType);
                writers.add(writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to build writers", e);
        }
    }

    @Override
    public void process(EntityContainer entityContainer) {
        try {
            if (this.entityType == entityContainer.getEntity().getType()) {
                final T entity = (T) entityContainer.getEntity();
                if (filters.stream().noneMatch(filter -> filter.test(entity))) {
                    Number next = idx.getAndIncrement() % partitions;
                    ParquetWriter<T> writer = writers.get(next.intValue());
                    writer.write(entity);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to write entity", e);
        }
    }

    @Override
    public void complete() {
        try {
            for (ParquetWriter<T> writer: writers) {
                writer.close();
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to close writers", e);
        }
    }

    public void addFilter(Predicate<T> predicate) {
        this.filters.add(predicate);
    }

    public void removeFilter(Predicate<T> predicate) {
        this.filters.remove(predicate);
    }

    @Override
    public void close() {

    }
}
