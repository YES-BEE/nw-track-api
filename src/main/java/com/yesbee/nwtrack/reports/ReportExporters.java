package com.yesbee.nwtrack.reports;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.awt.Color;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ReportExporters {
    private ReportExporters(){}

    static void write(ReportModels.Format format,ReportModels.Document document,
                      ReportModels.RowSource rows,OutputStream output) throws IOException {
        switch(format){
            case CSV -> csv(document,rows,output);
            case XLSX -> xlsx(document,rows,output);
            case PDF -> pdf(document,rows,output);
        }
    }

    private static void csv(ReportModels.Document document,ReportModels.RowSource rows,OutputStream output) throws IOException {
        var section=document.sections().stream().filter(ReportModels.Section::csvPrimary).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Report has no CSV detail section"));
        var writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8),64*1024);
        writeCsvLine(writer,section.columns().stream().map(ReportModels.Column::label).toList());
        rows.stream(section.query(),section.columns(),row->{
            List<String> values=new ArrayList<>(section.columns().size());
            for(var column:section.columns()){
                Object value=row.get(column.key());
                String rendered=text(value,document.timezone());
                values.add(value instanceof CharSequence?safeSpreadsheetText(rendered):rendered);
            }
            writeCsvLine(writer,values);
        });
        writer.flush();
    }

    private static void writeCsvLine(Writer writer,List<String> values) throws IOException {
        for(int i=0;i<values.size();i++){
            if(i>0)writer.write(',');
            String value=Objects.requireNonNullElse(values.get(i),"");
            writer.write('"');writer.write(value.replace("\"","\"\""));writer.write('"');
        }
        writer.write("\r\n");
    }

    private static void xlsx(ReportModels.Document document,ReportModels.RowSource rows,OutputStream output) throws IOException {
        SXSSFWorkbook workbook=new SXSSFWorkbook(200);
        workbook.setCompressTempFiles(true);
        try{
            CellStyle header=workbook.createCellStyle();
            Font font=workbook.createFont();font.setBold(true);header.setFont(font);
            CellStyle wrapped=workbook.createCellStyle();wrapped.setWrapText(true);
            for(var section:document.sections()){
                Sheet sheet=workbook.createSheet(sheetName(section.name(),workbook));
                Row metadata=sheet.createRow(0);
                metadata.createCell(0).setCellValue(document.title());
                metadata.createCell(1).setCellValue(document.from()+" to "+document.to());
                metadata.createCell(2).setCellValue("Snapshot: "+document.snapshotAt());
                Row heading=sheet.createRow(2);
                for(int i=0;i<section.columns().size();i++){
                    Cell cell=heading.createCell(i);cell.setCellValue(section.columns().get(i).label());cell.setCellStyle(header);
                    sheet.setColumnWidth(i,Math.min(50,Math.max(14,section.columns().get(i).label().length()+4))*256);
                }
                int[] index={3};
                rows.stream(section.query(),section.columns(),values->{
                    Row row=sheet.createRow(index[0]++);
                    for(int i=0;i<section.columns().size();i++){
                        Object value=values.get(section.columns().get(i).key());
                        Cell cell=row.createCell(i);
                        if(value instanceof Number number)cell.setCellValue(number.doubleValue());
                        else if(value instanceof Boolean bool)cell.setCellValue(bool);
                        else {cell.setCellValue(safeSpreadsheetText(text(value,document.timezone())));cell.setCellStyle(wrapped);}
                    }
                });
                sheet.createFreezePane(0,3);
                sheet.setAutoFilter(new CellRangeAddress(2,Math.max(2,index[0]-1),0,section.columns().size()-1));
            }
            workbook.write(output);output.flush();
        }finally{
            workbook.dispose();
            workbook.close();
        }
    }

    private static String sheetName(String requested,Workbook workbook){
        String base=WorkbookUtil.createSafeSheetName(requested);
        if(base.length()>31)base=base.substring(0,31);
        String candidate=base;int suffix=2;
        while(workbook.getSheet(candidate)!=null){
            String ending=" "+suffix++;candidate=base.substring(0,Math.min(base.length(),31-ending.length()))+ending;
        }
        return candidate;
    }

    private static void pdf(ReportModels.Document report,ReportModels.RowSource rows,OutputStream output) throws IOException {
        Document pdf=new Document(PageSize.A3.rotate(),24,24,28,28);
        try{
            PdfWriter.getInstance(pdf,output);
            pdf.addTitle(report.title());pdf.addAuthor("NW Track");pdf.addCreationDate();
            pdf.open();
            var title=FontFactory.getFont(FontFactory.HELVETICA_BOLD,16);
            var subtitle=FontFactory.getFont(FontFactory.HELVETICA,9);
            var sectionFont=FontFactory.getFont(FontFactory.HELVETICA_BOLD,11);
            pdf.add(new Paragraph(report.title(),title));
            pdf.add(new Paragraph(report.factoryName()+" | "+report.from()+" to "+report.to()+
                " | "+report.timezone()+" | Snapshot "+report.snapshotAt(),subtitle));
            pdf.add(Chunk.NEWLINE);
            for(var section:report.sections()){
                pdf.add(new Paragraph(section.name(),sectionFont));
                List<Map<String,Object>> chunk=new ArrayList<>(100);
                boolean[] headerNeeded={true};
                rows.stream(section.query(),section.columns(),row->{
                    chunk.add(row);
                    if(chunk.size()==100){
                        addPdfTable(pdf,section.columns(),chunk,report.timezone(),headerNeeded[0]);
                        headerNeeded[0]=false;chunk.clear();
                    }
                });
                if(!chunk.isEmpty()||headerNeeded[0])addPdfTable(pdf,section.columns(),chunk,report.timezone(),headerNeeded[0]);
                pdf.add(Chunk.NEWLINE);
            }
        }catch(DocumentException ex){
            throw new IOException("Unable to generate PDF report",ex);
        }finally{
            if(pdf.isOpen())pdf.close();
        }
    }

    private static void addPdfTable(Document pdf,List<ReportModels.Column> columns,List<Map<String,Object>> rows,
                                    String timezone,boolean header) throws DocumentException {
        PdfPTable table=new PdfPTable(columns.size());table.setWidthPercentage(100);table.setSplitRows(true);
        var headerFont=FontFactory.getFont(FontFactory.HELVETICA_BOLD,6);
        var bodyFont=FontFactory.getFont(FontFactory.HELVETICA,6);
        if(header){
            table.setHeaderRows(1);
            for(var column:columns){
                PdfPCell cell=new PdfPCell(new Phrase(column.label(),headerFont));
                cell.setBackgroundColor(new Color(225,230,236));cell.setPadding(3);table.addCell(cell);
            }
        }
        for(var row:rows)for(var column:columns){
            PdfPCell cell=new PdfPCell(new Phrase(text(row.get(column.key()),timezone),bodyFont));
            cell.setPadding(2);table.addCell(cell);
        }
        pdf.add(table);
    }

    private static String text(Object value,String timezone){
        if(value==null)return "";
        if(value instanceof OffsetDateTime dateTime)
            return dateTime.atZoneSameInstant(ZoneId.of(timezone)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        if(value instanceof Timestamp timestamp)
            return timestamp.toInstant().atZone(ZoneId.of(timezone)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        if(value instanceof BigDecimal decimal)return decimal.stripTrailingZeros().toPlainString();
        return value.toString();
    }

    static String safeSpreadsheetText(String value){
        if(value==null||value.isEmpty())return Objects.requireNonNullElse(value,"");
        char first=value.charAt(0);
        return first=='='||first=='+'||first=='-'||first=='@'||first=='\t'||first=='\r'?"'"+value:value;
    }
}
