package project.ktc.springboot_app.stripe.services;

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import project.ktc.springboot_app.course.entity.Course;
import project.ktc.springboot_app.payment.interfaces.PaymentService;
import project.ktc.springboot_app.stripe.dto.PriceCalculationResponse;

import java.net.MalformedURLException;
import java.net.URL;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for creating Stripe Checkout Sessions
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StripeCheckoutService {

        private final PaymentService paymentService;

        @Value("${app.frontend.url:}")
        private String frontendUrl;

        @Value("${app.frontend.url.default:https://sybau-education.vercel.app}")
        private String defaultFrontendUrl;

        /**
         * Creates a Stripe Checkout Session for course enrollment
         *
         * @param userId The user ID making the purchase
         * @param course The course to be purchased
         * @return The Stripe Checkout Session
         * @throws StripeException if there's an error creating the session
         */
        public Session createCheckoutSession(String userId, Course course) throws StripeException {
                // Use original price without discount
                PriceCalculationResponse priceCalc = PriceCalculationResponse.builder()
                                .originalPrice(course.getPrice())
                                .finalPrice(course.getPrice())
                                .discountAmount(BigDecimal.ZERO)
                                .discountApplied(false)
                                .build();
                return createCheckoutSessionWithDiscount(userId, course, priceCalc);
        }

        /**
         * Creates a Stripe Checkout Session with discount applied
         *
         * @param userId           The user ID making the purchase
         * @param course           The course to be purchased
         * @param priceCalculation The price calculation with discount details
         * @return The Stripe Checkout Session
         * @throws StripeException if there's an error creating the session
         */
        public Session createCheckoutSessionWithDiscount(String userId, Course course,
                        PriceCalculationResponse priceCalculation) throws StripeException {
                log.info("Creating checkout session for user {} and course {} with final price {}",
                                userId, course.getId(), priceCalculation.getFinalPrice());

                // Create payment record with final price
                String paymentId = paymentService.createPayment(
                                userId,
                                course.getId(),
                                priceCalculation.getFinalPrice().doubleValue(),
                                null // Will be updated after session creation
                );

                // Create metadata to track the purchase and discount
                Map<String, String> metadata = new HashMap<>();
                metadata.put("userId", userId);
                metadata.put("courseId", course.getId());
                metadata.put("paymentId", paymentId);
                metadata.put("originalPrice", priceCalculation.getOriginalPrice().toString());
                metadata.put("finalPrice", priceCalculation.getFinalPrice().toString());
                metadata.put("discountAmount", priceCalculation.getDiscountAmount().toString());
                if (priceCalculation.isDiscountApplied() && priceCalculation.getAppliedDiscountCode() != null) {
                        metadata.put("discountCode", priceCalculation.getAppliedDiscountCode());
                }

                // Validate and normalize frontend URL
                String normalizedFrontendUrl = normalizeFrontendUrl();
                log.debug("Using frontend URL: {}", normalizedFrontendUrl);

                // Build session parameters
                SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                                .setMode(SessionCreateParams.Mode.PAYMENT)
                                .setSuccessUrl(
                                                normalizedFrontendUrl + "/courses/" + course.getSlug()
                                                                + "/success?session_id={CHECKOUT_SESSION_ID}")
                                .setCancelUrl(normalizedFrontendUrl + "/courses/" + course.getSlug() + "/cancel")
                                .putAllMetadata(metadata)
                                .setLocale(SessionCreateParams.Locale.EN) // Set locale to English
                                .addLineItem(
                                                SessionCreateParams.LineItem.builder()
                                                                .setQuantity(1L)
                                                                .setPriceData(
                                                                                SessionCreateParams.LineItem.PriceData
                                                                                                .builder()
                                                                                                .setCurrency("usd")
                                                                                                .setUnitAmount(convertToStripeAmount(
                                                                                                                priceCalculation.getFinalPrice()))
                                                                                                .setProductData(
                                                                                                                SessionCreateParams.LineItem.PriceData.ProductData
                                                                                                                                .builder()
                                                                                                                                .setName(course.getTitle())
                                                                                                                                .setDescription(buildProductDescription(
                                                                                                                                                course,
                                                                                                                                                priceCalculation))
                                                                                                                                .addImage(course.getThumbnailUrl() != null
                                                                                                                                                ? course.getThumbnailUrl()
                                                                                                                                                : "")
                                                                                                                                .build())
                                                                                                .build())
                                                                .build());

                // Add customer email if available
                // You might want to fetch user email here if needed
                // paramsBuilder.setCustomerEmail(userEmail);

                // Configure payment methods
                paramsBuilder.addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD);

                // Set automatic tax calculation if configured
                // paramsBuilder.setAutomaticTax(
                // SessionCreateParams.AutomaticTax.builder()
                // .setEnabled(true)
                // .build()
                // );

                SessionCreateParams params = paramsBuilder.build();

                try {
                        Session session = Session.create(params);
                        log.info("Checkout session created successfully: {}", session.getId());

                        // Update payment record with session ID
                        paymentService.updatePaymentSessionId(paymentId, session.getId());
                        log.info("Payment {} updated with session ID {}", paymentId, session.getId());

                        return session;
                } catch (StripeException e) {
                        log.error("Failed to create checkout session: {}", e.getMessage(), e);
                        throw e;
                }
        }

        /**
         * Converts price to Stripe amount (smallest currency unit)
         * For USD, this means converting dollars to cents
         */
        private Long convertToStripeAmount(BigDecimal price) {
                return price.multiply(BigDecimal.valueOf(100)).longValue();
        }

        /**
         * Normalizes and validates the frontend URL for Stripe
         * Ensures URL has a valid scheme (http/https)
         * 
         * @return validated frontend URL with proper scheme
         * @throws IllegalStateException if URL is invalid or not configured
         */
        private String normalizeFrontendUrl() {
                String urlToUse = frontendUrl != null && !frontendUrl.isBlank() ? frontendUrl : defaultFrontendUrl;

                // Validate the URL has a scheme
                if (urlToUse == null || urlToUse.isBlank()) {
                        log.error("Frontend URL is not configured. Set FRONTEND_URL environment variable or app.frontend.url property");
                        throw new IllegalStateException(
                                        "Frontend URL is not configured. Please set FRONTEND_URL environment variable or app.frontend.url.default property");
                }

                try {
                        // Try to parse as URL to validate it has a scheme
                        new URL(urlToUse);
                        return urlToUse;
                } catch (MalformedURLException e) {
                        log.error("Invalid frontend URL: {}. Error: {}", urlToUse, e.getMessage());
                        throw new IllegalStateException("Invalid frontend URL: " + urlToUse
                                        + ". URL must include scheme (https:// or http://)", e);
                }
        }

        /**
         * Builds product description including discount information
         */
        private String buildProductDescription(Course course, PriceCalculationResponse priceCalculation) {
                StringBuilder description = new StringBuilder(
                                course.getDescription() != null ? course.getDescription() : "");

                if (priceCalculation.isDiscountApplied()) {
                        if (description.length() > 0) {
                                description.append(" ");
                        }
                        description.append(String.format("(Original: $%.2f, Discount: $%.2f with code %s)",
                                        priceCalculation.getOriginalPrice(),
                                        priceCalculation.getDiscountAmount(),
                                        priceCalculation.getAppliedDiscountCode()));
                }

                return description.toString();
        }

        /**
         * Creates a checkout session for a subscription (if needed later)
         */
        public Session createSubscriptionCheckoutSession(String userId, String priceId) throws StripeException {
                log.info("Creating subscription checkout session for user {} and price {}", userId, priceId);

                String normalizedFrontendUrl = normalizeFrontendUrl();

                Map<String, String> metadata = new HashMap<>();
                metadata.put("userId", userId);
                metadata.put("type", "subscription");

                SessionCreateParams params = SessionCreateParams.builder()
                                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                                .setSuccessUrl(normalizedFrontendUrl
                                                + "/subscription/success?session_id={CHECKOUT_SESSION_ID}")
                                .setCancelUrl(normalizedFrontendUrl + "/subscription/cancel")
                                .putAllMetadata(metadata)
                                .setLocale(SessionCreateParams.Locale.EN)
                                .addLineItem(
                                                SessionCreateParams.LineItem.builder()
                                                                .setQuantity(1L)
                                                                .setPrice(priceId)
                                                                .build())
                                .build();

                try {
                        Session session = Session.create(params);
                        log.info("Subscription checkout session created successfully: {}", session.getId());
                        return session;
                } catch (StripeException e) {
                        log.error("Failed to create subscription checkout session: {}", e.getMessage(), e);
                        throw e;
                }
        }
}
