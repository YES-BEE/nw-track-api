package com.yesbee.nwtrack.reports;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/{reportId}/export")
    @Operation(summary="Stream an individual report export")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable UUID reportId,
            @RequestParam String format,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) UUID styleId,
            @RequestParam(required=false) UUID lotId,
            @RequestParam(required=false) UUID stationId,
            @RequestParam(required=false) UUID processId,
            @RequestParam(required=false) UUID operatorId,
            @RequestParam(required=false) String status){
        var category=reports.category(reportId);
        var request=new ReportModels.Request(category.type(),ReportModels.Format.parse(format),
            from,to,styleId,lotId,stationId,processId,operatorId,status);
        var prepared=reports.prepare(category,request);
        UUID exportId=reports.recordRequest(prepared);
        String filename="nw-track_"+request.type().key+"_"+from+"_to_"+to+"."+request.format().extension;
        StreamingResponseBody body=output->reports.write(prepared,exportId,output);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(request.format().contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header("X-Report-Export-ID",exportId.toString())
            .body(body);
    }
}
