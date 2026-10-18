package cn.governance;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import java.nio.charset.StandardCharsets;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
@RestController
public class BatchController {
    private final BatchIntake service;
    public BatchController(BatchIntake service){this.service=service;}
    @GetMapping("/api/batches/mappings") public Object mappings(Authentication a){return service.mappings(a);}
    @PostMapping("/api/batches/mappings") public Object mapping(Authentication a,@RequestBody BatchIntake.Mapping m){return service.mapping(a,m);}
    @GetMapping("/api/batches") public Object batches(Authentication a){return service.batches(a);}
    @GetMapping("/api/batches/{id}") public Object rows(Authentication a,@PathVariable long id){return service.rows(a,id);}
    @GetMapping("/api/batches/template") public ResponseEntity<byte[]> template(Authentication a,@RequestParam long sourceId,@RequestParam(required=false) Long mappingId,@RequestParam(defaultValue="csv") String format){return download(service.template(a,sourceId,mappingId,format),"template."+("xlsx".equals(format)?"xlsx":"csv"));}
    @PostMapping("/api/batches") public Object upload(Authentication a,@RequestParam long sourceId,@RequestParam(required=false) Long mappingId,@RequestParam MultipartFile file) throws java.io.IOException{return service.upload(a,sourceId,mappingId,Objects.toString(file.getOriginalFilename(),""),file.getBytes());}
    @GetMapping("/api/batches/{id}/failures") public ResponseEntity<byte[]> failures(Authentication a,@PathVariable long id){return download(service.failures(a,id),"failures.csv");}
    @GetMapping("/api/batches/{id}/original") public ResponseEntity<byte[]> original(Authentication a,@PathVariable long id){var file=service.original(a,id);return download(file.bytes(),file.filename());}
    private ResponseEntity<byte[]> download(byte[] bytes,String name){
        String type=name.toLowerCase(Locale.ROOT).endsWith(".csv")?"text/csv; charset=UTF-8":name.toLowerCase(Locale.ROOT).endsWith(".xlsx")?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"application/octet-stream";
        return ResponseEntity.ok().header("Content-Disposition",ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString()).header("Content-Type",type).body(bytes);
    }
    @GetMapping("/api/batches/credentials") public Object credentials(Authentication a){return service.credentials(a);}
    @PostMapping("/api/batches/credentials") public Object credential(Authentication a,@RequestBody BatchIntake.Credential c){return service.credential(a,c);}
    @DeleteMapping("/api/batches/credentials/{id}") public void disable(Authentication a,@PathVariable long id){service.disable(a,id);}
    @GetMapping("/api/batches/receipts") public Object receipts(Authentication a){return service.receipts(a);}
    @PostMapping("/api/external/events") public Object external(@RequestHeader(value="Authorization",defaultValue="") String bearer,@RequestBody List<BatchIntake.External> items){
        InputChecks.require(bearer.startsWith("Bearer ")&&items.size()>0&&items.size()<=100,"请提供Bearer凭据，批次限1至100条");var token=bearer.substring(7);service.validateCredential(token);List<Object> results=new ArrayList<>();
        for(var item:items)try{results.add(service.external(token,item));}catch(Exception e){String error=e instanceof org.springframework.web.server.ResponseStatusException r?Objects.toString(r.getReason(),"接入失败"):"接入失败";service.externalFailure(token,item,error);results.add(Map.of("externalId",item==null?"":Objects.toString(item.externalId(),""),"error",error));}return results;
    }
}
@Component
class BatchWorker {
    private final JdbcTemplate jdbc;private final BatchIntake service;
    BatchWorker(JdbcTemplate jdbc,BatchIntake service){this.jdbc=jdbc;this.service=service;}
    @Scheduled(fixedDelay=1000) public void tick(){for(long id:jdbc.queryForList("SELECT id FROM tb_import_row WHERE state='PENDING' ORDER BY id LIMIT 20",Long.class)){try{service.process(id);}catch(Exception e){String error=e instanceof org.springframework.web.server.ResponseStatusException r?Objects.toString(r.getReason(),"校验失败"):"处理失败，请检查数据与账号状态";jdbc.update("UPDATE tb_import_row SET state='FAILED',error=? WHERE id=? AND state='PENDING'",error,id);}}}
}
