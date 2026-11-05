
package br.com.fixflow.shared.web.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @org.springframework.beans.factory.annotation.Value("${fixflow.auth.basic-enabled:false}")
    private boolean legacyBasic;


    @ExceptionHandler(br.com.fixflow.shared.application.registration.EmailAlreadyRegisteredException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateEmail() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "E-mail já cadastrado."));
    }

    @ExceptionHandler(br.com.fixflow.shared.application.registration.InvalidRegistrationException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRegistration() {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Confira os dados do cadastro. Use uma senha com pelo menos 8 caracteres, letra maiúscula, letra minúscula e número. Se a senha for muito longa, reduza o tamanho."));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody() {
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Corpo da requisição inválido."));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleInvalidArgument() {
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parâmetro inválido."));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate", legacyBasic ? "Basic realm=\"FixFlow\"" : "Bearer")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Autenticação obrigatória."));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Acesso à oficina não autorizado."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {

        Map<String, String> errors = new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.putIfAbsent(
                        error.getField(),
                        error.getDefaultMessage() != null
                                ? error.getDefaultMessage()
                                : "Valor inválido"
                ));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Um ou mais campos informados são inválidos."
        );

        problem.setTitle("Erro de validação");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errors", errors);

        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(br.com.fixflow.modules.workorders.domain.InvalidStateTransitionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidTransition(br.com.fixflow.modules.workorders.domain.InvalidStateTransitionException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setProperty("code", "INVALID_STATE_TRANSITION");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
    @ExceptionHandler(br.com.fixflow.modules.workorders.domain.WorkOrderVersionConflictException.class)
    public ResponseEntity<ProblemDetail> handleWorkOrderVersion(br.com.fixflow.modules.workorders.domain.WorkOrderVersionConflictException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setProperty("code", "WORK_ORDER_VERSION_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
    @ExceptionHandler(br.com.fixflow.modules.workorders.domain.WorkOrderTransitionDeniedException.class)
    public ResponseEntity<ProblemDetail> handleTransitionDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Alteração de estado não autorizada."));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleStatus(org.springframework.web.server.ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason()));
    }

    @ExceptionHandler(br.com.fixflow.modules.tenants.domain.InsufficientStockException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientStock() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "Estoque insuficiente.");
        problem.setProperty("code", "INSUFFICIENT_STOCK");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem);
    }

    @ExceptionHandler(br.com.fixflow.modules.tenants.domain.PlanLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handlePlanLimit(br.com.fixflow.modules.tenants.domain.PlanLimitExceededException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setProperty("code", "PLAN_LIMIT_REACHED");
        problem.setProperty("resource", exception.resource());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler({jakarta.validation.ConstraintViolationException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    public ResponseEntity<ProblemDetail> handleInvalidParameters(Exception exception) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parâmetros inválidos."));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleOversizedUpload() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "O limite é de 10 MB por arquivo."));
    }
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<ProblemDetail> handleMissingUpload() {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Selecione um arquivo para enviar."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocorreu um erro interno ao processar a solicitação."
        );

        problem.setTitle("Erro interno");
        problem.setInstance(URI.create(request.getRequestURI()));

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem);
    }
}
