package com.yesbee.nwtrack.reports;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class ReportModels {
    private ReportModels(){}

    enum Format {
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","xlsx"),
        CSV("text/csv;charset=UTF-8","csv"),
        PDF("application/pdf","pdf");

        final String contentType;
        final String extension;
        Format(String contentType,String extension){this.contentType=contentType;this.extension=extension;}
        static Format parse(String value){
            if(value==null||value.isBlank())throw new IllegalArgumentException("format is required");
            try{return valueOf(value.trim().toUpperCase(Locale.ROOT));}
            catch(IllegalArgumentException ex){throw new IllegalArgumentException("format must be xlsx, csv or pdf");}
        }
    }

    enum Type {
        STYLE_LOT("style-lot-production",
            Set.of(Format.XLSX,Format.CSV,Format.PDF),Set.of("styleIds","lotIds","stationId","processId","status")),
        STATION_TIME("station-processing-time",
            Set.of(Format.XLSX,Format.CSV,Format.PDF),Set.of("styleIds","lotIds","stationId","processId","operatorId","status")),
        DELAY("delay-bottleneck",
            Set.of(Format.XLSX,Format.CSV,Format.PDF),Set.of("styleIds","lotIds","stationId","processId","operatorId","status")),
        MOVEMENT("lot-movement-traceability",
            Set.of(Format.XLSX,Format.CSV,Format.PDF),Set.of("styleIds","lotIds","stationId","operatorId","status")),
        STORAGE("storage-waiting-time",
            Set.of(Format.XLSX,Format.CSV,Format.PDF),Set.of("styleIds","lotIds","stationId","operatorId","status")),
        TROLLEY("trolley-utilization",
            Set.of(Format.XLSX,Format.CSV,Format.PDF),Set.of("styleIds","lotIds","status")),
        OPERATOR("operator-activity",
            Set.of(Format.XLSX,Format.CSV,Format.PDF),Set.of("styleIds","lotIds","stationId","processId","operatorId","status")),
        DAILY("daily-production-summary",
            Set.of(Format.XLSX,Format.PDF),Set.of("styleIds","lotIds","stationId","processId","status"));

        final String key;
        final Set<Format> formats;
        final Set<String> filters;
        Type(String key,Set<Format> formats,Set<String> filters){
            this.key=key;this.formats=formats;this.filters=filters;
        }
        static Type parse(String key){
            return Arrays.stream(values()).filter(value->value.key.equals(key)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Unknown report key: "+key));
        }
    }

    record Request(Type type,Format format,LocalDate from,LocalDate to,List<UUID> styleIds,List<UUID> lotIds,
                   UUID stationId,UUID processId,UUID operatorId,String status){
        Request {
            Objects.requireNonNull(type);Objects.requireNonNull(format);
            if(from==null||to==null)throw new IllegalArgumentException("from and to are required");
            if(from.isAfter(to))throw new IllegalArgumentException("from must be on or before to");
            if(!type.formats.contains(format))
                throw new IllegalArgumentException(type.key+" does not support "+format.name().toLowerCase(Locale.ROOT));
            status=status==null?null:status.trim().toUpperCase(Locale.ROOT);
            if(status!=null&&status.isEmpty())status=null;
            styleIds=ids("styleIds",styleIds);lotIds=ids("lotIds",lotIds);
            validateFilter(type,"styleIds",styleIds.isEmpty()?null:styleIds);
            validateFilter(type,"lotIds",lotIds.isEmpty()?null:lotIds);
            validateFilter(type,"stationId",stationId);validateFilter(type,"processId",processId);
            validateFilter(type,"operatorId",operatorId);validateFilter(type,"status",status);
        }
        private static void validateFilter(Type type,String name,Object value){
            if(value!=null&&!type.filters.contains(name))
                throw new IllegalArgumentException(name+" is not supported by report "+type.key);
        }
        private static List<UUID> ids(String name,List<UUID> values){
            if(values==null||values.isEmpty())return List.of();
            if(values.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException(name+" cannot contain null");
            if(new java.util.HashSet<>(values).size()!=values.size())throw new IllegalArgumentException(name+" cannot contain duplicates");
            if(values.size()>100)throw new IllegalArgumentException(name+" cannot contain more than 100 values");
            return List.copyOf(values);
        }
    }

    record Category(UUID id,Type type,String title){}
    record Prepared(Category category,Request request,String lotPredicate,String stationPredicate,String processPredicate){}
    record Column(String key,String label){}
    record Query(String sql,List<Object> args){}
    record Section(String name,List<Column> columns,Query query,boolean csvPrimary){}
    record Document(String title,String factoryName,String timezone,LocalDate from,LocalDate to,
                    OffsetDateTime snapshotAt,List<Section> sections){}

    @FunctionalInterface interface RowConsumer { void accept(Map<String,Object> row) throws IOException; }
    @FunctionalInterface interface RowSource { void stream(Query query,List<Column> columns,RowConsumer consumer) throws IOException; }
}
