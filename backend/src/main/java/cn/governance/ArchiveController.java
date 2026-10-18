package cn.governance;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
@RestController
@RequestMapping("/api/archive")
public class ArchiveController {
    private final ArchiveChanges service;
    public ArchiveController(ArchiveChanges service) {this.service=service;}
    @GetMapping("/requests") public Object requests(Authentication a) {return service.requests(a);}
    @PostMapping("/records/{id}/requests") public Object request(Authentication a,@PathVariable long id,@RequestBody ArchiveChanges.Request r) {return service.request(a,id,r);}
    @PostMapping("/requests/{id}/review") public Object review(Authentication a,@PathVariable long id,@RequestBody ArchiveChanges.Decision r) {return service.decide(a,id,r);}
    @GetMapping("/people") public Object people(Authentication a,@RequestParam(defaultValue="") String q) {return service.people(a,q);}
    @PutMapping("/people/{id}") public Object profile(Authentication a,@PathVariable long id,@RequestBody ArchiveChanges.Profile r) {return service.profile(a,id,r);}
    @PostMapping("/people/{id}/merge") public Object merge(Authentication a,@PathVariable long id,@RequestBody ArchiveChanges.Move r) {return service.merge(a,id,r);}
    @PostMapping("/records/{id}/split") public Object split(Authentication a,@PathVariable long id,@RequestBody ArchiveChanges.Move r) {return service.split(a,id,r);}
    @GetMapping("/history") public Object history(Authentication a) {return service.history(a);}
}
