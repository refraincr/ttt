package cn.governance;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
@RestController
@RequestMapping("/api/cases")
public class CaseController {
    private final AlertService alerts;
    private final HandlingTasks tasks;
    public CaseController(AlertService alerts,HandlingTasks tasks) {this.alerts=alerts;this.tasks=tasks;}
    @GetMapping("/alerts") public Object alerts(Authentication a,@RequestParam(defaultValue="") String level,@RequestParam(defaultValue="") String name,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String source,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="false") boolean backlog,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return alerts.list(a,level,name,keyword,source,departmentId,from,to,state,backlog,page,size);}
    @GetMapping("/alerts/{id}") public Object alert(Authentication a,@PathVariable long id) {return alerts.detail(a,id);}
    @PostMapping("/alerts/{id}/reviews") public Object assess(Authentication a,@PathVariable long id,@RequestBody AlertService.Review input) {return alerts.review(a,id,input);}
    @GetMapping("/settings") public Object settings(Authentication a) {return alerts.settings(a);}
    @PutMapping("/settings") public Object settings(Authentication a,@RequestBody AlertService.Settings input) {return alerts.configure(a,input);}
    @PostMapping("/alerts/{id}/tasks") public Object dispatch(Authentication a,@PathVariable long id,@RequestBody HandlingTasks.Dispatch input) {return tasks.dispatch(a,id,input);}
    @PostMapping("/tasks/{id}/confirm") public Object confirm(Authentication a,@PathVariable long id,@RequestBody HandlingTasks.Confirmation input) {return tasks.confirm(a,id,input);}
    @GetMapping("/tasks") public Object tasks(Authentication a,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return tasks.list(a,state,page,size);}
    @GetMapping("/tasks/{id}") public Object task(Authentication a,@PathVariable long id) {return tasks.detail(a,id);}
    @PostMapping("/tasks/{id}/progress") public Object progress(Authentication a,@PathVariable long id,@RequestBody HandlingTasks.Progress input) {return tasks.progress(a,id,input);}
    @PostMapping("/tasks/{id}/reviews") public Object review(Authentication a,@PathVariable long id,@RequestBody HandlingTasks.Review input) {return tasks.review(a,id,input);}
    @PostMapping("/attachments") public Object upload(Authentication a,@RequestParam MultipartFile file) throws IOException {return tasks.upload(a,file);}
    @GetMapping("/attachments/{id}") public ResponseEntity<byte[]> attachment(Authentication a,@PathVariable long id) {
        var file=tasks.download(a,id);return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(),StandardCharsets.UTF_8).build().toString()).header("X-Content-Type-Options","nosniff").body(file.content());
    }
}
