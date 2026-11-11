package com.yesbee.nwtrack.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;

/** Paging is opt-out only for fixed structures (for example a derived route).
 * Queries keep their authorization predicates; filtering and limits run in PostgreSQL.
 */
public final class Pages {
    private Pages(){}
    public record Options(int page,int size,String search){
        public Options{
            if(page<0||size<1||size>100)throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 100");
            search=search==null?"":search.trim();
            if(search.length()>200)throw new IllegalArgumentException("search must be at most 200 characters");
        }
        public long offset(){return (long)page*size;}
    }
    public static Options options(){
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))return new Options(0,20,"");
        var request=attributes.getRequest();
        try{return new Options(request.getParameter("page")==null?0:Integer.parseInt(request.getParameter("page")),request.getParameter("size")==null?20:Integer.parseInt(request.getParameter("size")),request.getParameter("search"));}
        catch(NumberFormatException ex){throw new IllegalArgumentException("page and size must be integers");}
    }
    public static ApiPage<Map<String,Object>> query(JdbcTemplate jdbc,String sql,Object... args){return query(jdbc,options(),sql,args);}
    public static ApiPage<Map<String,Object>> query(JdbcTemplate jdbc,Options options,String sql,Object... args){
        // Use an ordinal to retain the endpoint's ordering after filtering. JSON is
        // used only for searching projected fields; never hidden table columns.
        String source=" from (select page_row.*,row_number() over () as __page_order from ("+sql+") page_row) page_source";
        List<Object> parameters=new ArrayList<>(Arrays.asList(args));
        if(!options.search().isEmpty()){
            source+=" where exists (select 1 from jsonb_each_text(to_jsonb(page_source)-'__page_order') field where strpos(lower(field.value),lower(?))>0)";
            parameters.add(options.search());
        }
        Long total=jdbc.queryForObject("select count(*)"+source,Long.class,parameters.toArray());
        parameters.add(options.size());parameters.add(options.offset());
        var items=jdbc.queryForList("select page_source.*"+source+" order by __page_order limit ? offset ?",parameters.toArray());
        items.forEach(row->row.remove("__page_order"));
        return new ApiPage<>(items,options.page(),options.size(),Objects.requireNonNullElse(total,0L));
    }
}
