package cn.governance;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.util.*;
@RestController
@RequestMapping("/api/intake")
public class IntakeController {
    private final IntakeService service;
    public IntakeController(IntakeService service) { this.service=service; }
    @GetMapping("/drafts") public Object drafts(Authentication a) { return service.drafts(a); }
    @GetMapping("/drafts/{id}") public Object draft(Authentication a,@PathVariable long id) { return service.draft(a,id); }
    @PostMapping("/drafts") public Object create(Authentication a,@RequestBody IntakeService.Entry e) { return service.save(a,null,e,null); }
    @PutMapping("/drafts/{id}") public Object save(Authentication a,@PathVariable long id,@RequestBody IntakeService.DraftWrite e) { return service.save(a,id,e.payload(),e.version()); }
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) @DeleteMapping("/drafts/{id}") public void delete(Authentication a,@PathVariable long id,@RequestParam long version) { service.delete(a,id,version); }
    @PostMapping("/drafts/{id}/submit") public Object submit(Authentication a,@PathVariable long id,@RequestBody IntakeService.Version e) { return service.submit(a,id,e.version()); }
    @GetMapping("/records/{id}") public Object record(Authentication a,@PathVariable long id) { return service.record(a,id); }
    @GetMapping("/identity-reviews") public Object reviews(Authentication a) { return service.reviews(a); }
    @PostMapping("/identity-reviews/{id}") public Object review(Authentication a,@PathVariable long id,@RequestBody IntakeService.Review e) { return service.review(a,id,e); }
    @GetMapping("/people") public Object people(Authentication a,@RequestParam(defaultValue="") String q) { return service.people(a,q); }
    @GetMapping("/duplicate-reviews") public Object duplicateReviews(Authentication a) { return service.duplicateReviews(a); }
    @GetMapping("/keyword-rules") public Object rules(Authentication a) { return service.keywordRules(a); }
    @PutMapping("/records/{id}/tags") public Object tags(Authentication a,@PathVariable long id,@RequestBody IntakeService.Tags e) { return service.tags(a,id,e); }
    @GetMapping("/people/{id}/risk") public Object risk(Authentication a,@PathVariable long id) { return service.risk(a,id); }
    @PostMapping("/records/{id}/duplicate-review") public Object duplicateReview(Authentication a,@PathVariable long id,@RequestBody IntakeService.DuplicateReview e) { return service.duplicateReview(a,id,e); }
    @GetMapping("/people/{id}") public Object person(Authentication a,@PathVariable long id) { return service.person(a,id); }
    @GetMapping("/records/{id}/duplicates") public Object duplicates(Authentication a,@PathVariable long id) { return service.candidates(a,id); }
    @GetMapping("/duplicate-settings") public Object settings(Authentication a) { return service.settings(a); }
    @PutMapping("/duplicate-settings") public Object settings(Authentication a,@RequestBody DuplicateScreening.Settings e) { return service.settings(a,e); }
    @GetMapping("/departments") public Object departments(Authentication a) { return service.departments(a); }
}

