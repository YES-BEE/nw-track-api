package com.yesbee.nwtrack.reports;

import com.lowagie.text.pdf.PdfReader;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportExportersTest {
    private static final List<ReportModels.Column> COLUMNS=List.of(
        new ReportModels.Column("name","Name"),
        new ReportModels.Column("count","Count"));

    @Test void csvStreamsQuotedUtf8RowsAndNeutralizesFormulas()throws Exception{
        var output=new ByteArrayOutputStream();
        ReportExporters.write(ReportModels.Format.CSV,document(ReportModels.Format.CSV),
            (query,columns,consumer)->{
                consumer.accept(Map.of("name","=SUM(1,2)","count",2));
                consumer.accept(Map.of("name","Málaga, \"North\"","count",3));
            },output);
        String csv=output.toString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\"Name\",\"Count\"\r\n");
        assertThat(csv).contains("\"'=SUM(1,2)\",\"2\"").contains("\"Málaga, \"\"North\"\"\",\"3\"");
    }

    @Test void xlsxUsesValidWorkbookAndNeutralizesFormulaText()throws Exception{
        var output=new ByteArrayOutputStream();
        ReportExporters.write(ReportModels.Format.XLSX,document(ReportModels.Format.XLSX),
            (query,columns,consumer)->consumer.accept(Map.of("name","-unsafe","count",7)),output);
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))){
            var sheet=workbook.getSheet("Details");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("'-unsafe");
            assertThat(sheet.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(7);
        }
    }

    @Test void pdfProducesAReadableDocument()throws Exception{
        var output=new ByteArrayOutputStream();
        ReportExporters.write(ReportModels.Format.PDF,document(ReportModels.Format.PDF),
            (query,columns,consumer)->consumer.accept(Map.of("name","Example","count",1)),output);
        var reader=new PdfReader(output.toByteArray());
        try{
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            assertThat(new String(reader.getPageContent(1))).isNotEmpty();
        }finally{reader.close();}
    }

    private static ReportModels.Document document(ReportModels.Format format){
        var section=new ReportModels.Section("Details",COLUMNS,new ReportModels.Query("unused",List.of()),true);
        return new ReportModels.Document("Example Report","Factory","Asia/Kolkata",
            LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),
            OffsetDateTime.parse("2026-10-31T12:00:00Z"),List.of(section));
    }
}
