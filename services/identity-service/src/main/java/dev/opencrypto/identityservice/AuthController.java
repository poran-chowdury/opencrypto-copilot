package dev.opencrypto.identityservice;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
 @GetMapping("/status") public Map<String,Object> status() {
  return Map.of("service","identity-service","status","starter-ready","note","Real authentication/JWT integration is not implemented.");
 }
}
