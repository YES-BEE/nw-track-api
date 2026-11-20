package com.yesbee.nwtrack.reports;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("isAuthenticated()")
@Tag(name="Reports and analysis")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports){this.reports=reports;}

    @GetMapping("/catalog")
    @Operation(summary="List available reports, formats and filters")
    public List<Map<String,Object>> catalog(){
        return reports.catalog();
    }

    @PostMapping("/{reportId}/export")
    @Operation(summary="Stream an individual report export")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable UUID reportId,
            @RequestBody ExportRequest body){
        var category=reports.category(reportId);
        var request=new ReportModels.Request(category.type(),ReportModels.Format.parse(body.format()),
            body.from(),body.to(),body.styleIds(),body.lotIds(),body.stationId(),body.processId(),body.operatorId(),body.status());
        var prepared=reports.prepare(category,request);
        UUID exportId=reports.recordRequest(prepared);
        String filename="nw-track_"+request.type().key+"_"+request.from()+"_to_"+request.to()+"."+request.format().extension;
        StreamingResponseBody stream=output->reports.write(prepared,exportId,output);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(request.format().contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header("X-Report-Export-ID",exportId.toString())
            .body(stream);
    }
    public record ExportRequest(String format,LocalDate from,LocalDate to,List<UUID> styleIds,List<UUID> lotIds,
                                UUID stationId,UUID processId,UUID operatorId,String status){}
}
