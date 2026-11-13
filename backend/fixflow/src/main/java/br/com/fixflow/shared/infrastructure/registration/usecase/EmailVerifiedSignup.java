package br.com.fixflow.shared.infrastructure.registration.usecase;

import br.com.fixflow.shared.application.registration.usecase.VerifiedSignup;
import br.com.fixflow.shared.application.registration.InvalidRegistrationException;
import br.com.fixflow.modules.identity.application.port.RefreshSecrets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class EmailVerifiedSignup implements VerifiedSignup {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final RefreshSecrets secrets;
    private final JavaMailSender mail;
    private final ObjectMapper mapper;
    private final String origin;
    private final String from;
    public EmailVerifiedSignup(JdbcTemplate jdbc,PasswordEncoder encoder,RefreshSecrets secrets,JavaMailSender mail,ObjectMapper mapper,
            @Value("${fixflow.auth.allowed-origin:http://localhost:4200}") String origin,@Value("${fixflow.mail.from:noreply@fixflow.local}") String from) {
        this.jdbc=jdbc;this.encoder=encoder;this.secrets=secrets;this.mail=mail;this.mapper=mapper;this.origin=origin.replaceAll("/+$","");this.from=from;
    }
    @Override @Transactional public void request(String company,String name,String email,String password,String plan,String remoteOrigin) {
        request(company,name,email,password,plan,remoteOrigin,null);
    }
    @Override @Transactional public void request(String company,String name,String email,String password,String plan,String remoteOrigin,Profile profile) {
        if(company==null||company.isBlank()||company.length()>150||name==null||name.isBlank()||name.length()>301||email==null||email.length()>254
            ||password==null||password.length()<8||password.getBytes(StandardCharsets.UTF_8).length>72
            ||!password.matches("^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])[\\s\\S]*$")||(plan==null||!java.util.Set.of("STARTER","PROFESSIONAL","BUSINESS").contains(plan))) throw new InvalidRegistrationException();
        if (profile!=null && (profile.document()==null || !profile.document().matches("(?:[0-9]{11}|[0-9]{14})")
            || profile.phone()==null || !profile.phone().matches("[0-9]{10,11}") || profile.address()==null)) throw new InvalidRegistrationException();
        String token=secrets.generate();
        boolean send=Boolean.TRUE.equals(jdbc.queryForObject("SELECT public.begin_verified_signup(?,?,?,?,?,?,?,?::jsonb)",Boolean.class,
            email.strip().toLowerCase(Locale.ROOT),name.strip(),company.strip(),plan,encoder.encode(password),secrets.hash(token),secrets.hash(remoteOrigin==null?"unknown":remoteOrigin),profile==null?null:mapper.writeValueAsString(profile)));
        if(!send) return; // Uniform response for existing accounts and throttled resends.
        var message=new SimpleMailMessage();message.setFrom(from);message.setTo(email.strip().toLowerCase(Locale.ROOT));
        message.setSubject("Confirme seu e-mail para iniciar o teste do FixFlow");
        message.setText("Olá, "+name.strip()+".\n\nConfirme seu e-mail para criar a assistência "+company.strip()+" "+("STARTER".equals(plan)?"e iniciar 14 dias de teste no plano Starter.":"no plano "+plan+". A ativação depende do pagamento da assinatura.")+"\n\n"
            +origin+"/confirmar-email#token="+token+"\n\nO link é válido por 24 horas. Confirme o cadastro para continuar.\nSe você não pediu este cadastro, ignore esta mensagem.");
        try { mail.send(message); }
        catch(MailException ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível enviar a confirmação. Tente novamente em instantes."); }
    }
    @Override @Transactional public Confirmation confirm(String token) {
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}")) throw new ResponseStatusException(HttpStatus.GONE,"Link inválido ou expirado. Solicite uma nova confirmação no cadastro.");
        String json=jdbc.queryForObject("SELECT public.confirm_verified_signup(?)::text",String.class,secrets.hash(token));
        if(json==null) throw new ResponseStatusException(HttpStatus.GONE,"Link inválido ou expirado. Solicite uma nova confirmação no cadastro.");
        return mapper.readValue(json,Confirmation.class);
    }
}
