package com.clinicemr.productivity;

import java.util.List;
import java.util.UUID;

import com.clinicemr.productivity.ProductivityDtos.FavouriteRequest;
import com.clinicemr.productivity.ProductivityDtos.FavouriteView;
import com.clinicemr.productivity.ProductivityDtos.SaveTemplateRequest;
import com.clinicemr.productivity.ProductivityDtos.TemplateView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in doctor's own templates and favourites. They follow the doctor across clinics. */
@RestController
@RequestMapping("/api/v1/me")
public class ProductivityController {

    private final TemplateService templates;
    private final FavouriteService favourites;

    public ProductivityController(TemplateService templates, FavouriteService favourites) {
        this.templates = templates;
        this.favourites = favourites;
    }

    @GetMapping("/templates")
    public List<TemplateView> templates() {
        return templates.list();
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateView createTemplate(@Valid @RequestBody SaveTemplateRequest req) {
        return templates.create(req);
    }

    @PutMapping("/templates/{id}")
    public TemplateView updateTemplate(@PathVariable UUID id, @Valid @RequestBody SaveTemplateRequest req) {
        return templates.update(id, req);
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(@PathVariable UUID id) {
        templates.delete(id);
    }

    @GetMapping("/favourites")
    public List<FavouriteView> favourites(@RequestParam String kind, @RequestParam(required = false) String q) {
        return favourites.list(kind, q);
    }

    @PostMapping("/favourites/pin")
    public FavouriteView pin(@Valid @RequestBody FavouriteRequest req) {
        return favourites.setPinned(req);
    }
}
