package cn.governance;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog = catalog; }
    @GetMapping("/api/admin/sources") public List<CatalogService.Source> sources(Authentication auth) { return catalog.sources(auth); }
    @PostMapping("/api/admin/sources") @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.Source createSource(Authentication auth, @RequestBody CatalogService.SourceWrite input) { return catalog.saveSource(auth, null, input); }
    @PutMapping("/api/admin/sources/{id}")
    public CatalogService.Source updateSource(Authentication auth, @PathVariable long id, @RequestBody CatalogService.SourceWrite input) { return catalog.saveSource(auth, id, input); }
    @GetMapping("/api/admin/dictionaries") public List<CatalogService.Dictionary> dictionaries(Authentication auth) { return catalog.dictionaries(auth); }
    @PostMapping("/api/admin/dictionaries") @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.Dictionary createDictionary(Authentication auth, @RequestBody CatalogService.DictionaryWrite input) { return catalog.saveDictionary(auth, null, input); }
    @PutMapping("/api/admin/dictionaries/{id}")
    public CatalogService.Dictionary updateDictionary(Authentication auth, @PathVariable long id, @RequestBody CatalogService.DictionaryWrite input) { return catalog.saveDictionary(auth, id, input); }
    @GetMapping("/api/options/sources") public List<CatalogService.Source> sourceOptions(Authentication auth) { return catalog.sourceOptions(auth); }
    @GetMapping("/api/options/dictionaries")
    public List<CatalogService.Dictionary> dictionaryOptions(Authentication auth, @RequestParam CatalogService.DictionaryKind kind) { return catalog.dictionaryOptions(auth, kind); }
    @GetMapping("/api/catalog/sources/{id}") public CatalogService.Source source(Authentication auth, @PathVariable long id) { return catalog.source(auth, id); }
    @GetMapping("/api/catalog/dictionaries/{id}") public CatalogService.Dictionary dictionary(Authentication auth, @PathVariable long id) { return catalog.dictionary(auth, id); }
}
