package com.clinicemr.rating;

import java.util.UUID;

import com.clinicemr.shared.security.AdminContext;
import com.clinicemr.shared.security.ClinicContext;
import com.clinicemr.shared.security.PatientContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Patients rate visits, clinics rate ClinicEMR, the super admin sees everything, and anyone sees what was allowed. */
@RestController
public class RatingController {

    private final RatingService ratings;

    public RatingController(RatingService ratings) {
        this.ratings = ratings;
    }

    public record RateVisit(Integer stars, String comment) {
    }

    /** The patient's rating of one of their signed visits; sent again, it changes the rating. */
    @PutMapping("/api/v1/patient/profiles/{profileId}/visits/{encounterId}/rating")
    public RatingService.MyRating rateVisit(PatientContext p, @PathVariable UUID profileId, @PathVariable UUID encounterId,
                                            @RequestBody RateVisit req) {
        return ratings.rateVisit(p, profileId, encounterId, req.stars(), req.comment());
    }

    /** How patients rated this clinic (a doctor who isn't an admin sees only their own), without patient names. */
    @GetMapping("/api/v1/clinic/ratings")
    public RatingService.ClinicRatings clinicRatings(ClinicContext ctx) {
        return ratings.clinicRatings(ctx);
    }

    public record RateApp(Integer stars, String comment, Boolean allowPublic) {
    }

    /** 204 until the clinic has rated ClinicEMR. */
    @GetMapping("/api/v1/clinic/app-rating")
    public ResponseEntity<RatingService.AppRating> appRating(ClinicContext ctx) {
        return ratings.appRating(ctx).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/api/v1/clinic/app-rating")
    public RatingService.AppRating saveAppRating(ClinicContext ctx, @RequestBody RateApp req) {
        return ratings.saveAppRating(ctx, req.stars(), req.comment(), Boolean.TRUE.equals(req.allowPublic()));
    }

    @GetMapping("/api/v1/admin/ratings")
    public RatingService.AdminRatings adminRatings(AdminContext admin) {
        return ratings.adminRatings();
    }

    /** No sign-in: the average rating and only the feedback clinics agreed to show, for the website. */
    @GetMapping("/api/v1/public/app-rating")
    public RatingService.PublicRating publicRating() {
        return ratings.publicRating();
    }
}
