package br.com.fixflow.modules.tenants.infrastructure;

import br.com.fixflow.modules.tenants.application.Onboarding;
import br.com.fixflow.shared.application.registration.dto.RegistrationAddress;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

@Service
public class JdbcOnboarding implements Onboarding {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AuditService audit;
    private final ObjectMapper mapper;
    public JdbcOnboarding(JdbcTemplate jdbc,AuthorizedTenantOperations tenants,CurrentAuthenticatedIdentity identity,AuditService audit,ObjectMapper mapper){
        this.jdbc=jdbc;this.tenants=tenants;this.identity=identity;this.audit=audit;this.mapper=mapper;
    }
    private <T>T owner(Function<AuthorizedMembership,T> operation){
        var id=identity.require().tenantId();if(id==null)throw new AccessDeniedException("Selecione uma assistência.");
        return tenants.execute(id,m->{if(!m.role().name().equals("OWNER"))throw new AccessDeniedException("Configuração exclusiva do proprietário.");return operation.apply(m);});
    }
    private View view(UUID tenant){
        var first=jdbc.queryForList("SELECT id FROM public.work_orders WHERE tenant_id=? ORDER BY created_at,id LIMIT 1",UUID.class,tenant);
        long customers=jdbc.queryForObject("SELECT count(*) FROM public.customers WHERE tenant_id=? AND deleted_at IS NULL",Long.class,tenant);
        long equipment=jdbc.queryForObject("SELECT count(*) FROM public.equipment WHERE tenant_id=? AND deleted_at IS NULL",Long.class,tenant);
        return jdbc.queryForObject("SELECT * FROM public.tenants WHERE id=?",(rs,n)->new View(rs.getTimestamp("onboarding_profile_completed_at")!=null,rs.getTimestamp("onboarding_completed_at")!=null,
            new Profile(rs.getString("name"),rs.getString("document"),rs.getString("phone"),new RegistrationAddress(rs.getString("street"),rs.getString("number"),rs.getString("complement"),rs.getString("neighborhood"),rs.getString("city"),rs.getString("state"),rs.getString("postal_code"))),
            customers,equipment,first.isEmpty()?null:first.getFirst()),tenant);
    }
    @Override public View load(){return owner(m->view(m.tenantId()));}
    @Override public View save(Profile p){return owner(m->{
        var a=p.address();
        jdbc.queryForObject("SELECT id FROM public.tenants WHERE id=? FOR UPDATE",UUID.class,m.tenantId());
        jdbc.update("""
            UPDATE public.tenants SET name=?,document=?,phone=?,street=?,number=?,complement=?,neighborhood=?,city=?,state=?,postal_code=?,
                address=concat_ws(', ',?,?,?,?,?,?,?),onboarding_profile_completed_at=coalesce(onboarding_profile_completed_at,clock_timestamp()) WHERE id=?
            """,p.companyName(),p.document(),p.phone(),a.street(),a.number(),a.complement(),a.neighborhood(),a.city(),a.state(),a.postalCode(),
                a.street(),a.number(),a.complement(),a.neighborhood(),a.city(),a.state(),a.postalCode(),m.tenantId());
        audit.record(m.userId(),"ONBOARDING_PROFILE_SAVED","TENANT",m.tenantId(),"{}");
        return view(m.tenantId());
    });}
    @Override public View complete(){return owner(m->{
        jdbc.queryForObject("SELECT id FROM public.tenants WHERE id=? FOR UPDATE",UUID.class,m.tenantId());
        var current=view(m.tenantId());
        if(!current.profileCompleted()||current.firstOrderId()==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Complete os dados da assistência e registre o primeiro atendimento.");
        if(jdbc.update("UPDATE public.tenants SET onboarding_completed_at=clock_timestamp() WHERE id=? AND onboarding_completed_at IS NULL",m.tenantId())==1)
            audit.record(m.userId(),"ONBOARDING_COMPLETED","TENANT",m.tenantId(),mapper.writeValueAsString(Map.of("firstOrderId",current.firstOrderId())));
        return view(m.tenantId());
    });}
    @Override public byte[] exportCsv(){return owner(m->{
        try(var bytes=new java.io.ByteArrayOutputStream();var zip=new java.util.zip.ZipOutputStream(bytes,java.nio.charset.StandardCharsets.UTF_8)) {
            String[][] tables={
                {"customers","clientes"},{"customer_contacts","contatos_dos_clientes"},{"equipment","equipamentos"},
                {"work_orders","ordens_de_servico"},{"work_order_items","itens_das_ordens"},{"work_order_diagnoses","diagnosticos"},
                {"quotes","orcamentos"},{"quote_items","itens_dos_orcamentos"},{"parts","pecas_e_estoque"},
                {"inventory_movements","movimentacoes_de_estoque"},{"payments","pagamentos"}
            };
            writeCsv(zip,"empresa","SELECT name,document,phone,street,number,complement,neighborhood,city,state,postal_code FROM public.tenants WHERE id=?",m.tenantId());
            for(var table:tables) writeCsv(zip,table[1],"SELECT * FROM public."+table[0]+" WHERE tenant_id=? ORDER BY id",m.tenantId());
            zip.putNextEntry(new java.util.zip.ZipEntry("LEIA-ME.txt"));
            zip.write(("Exportação do FixFlow\r\n\r\nExtraia o ZIP e abra os arquivos CSV no Excel ou LibreOffice.\r\n"+
                "Codificação: UTF-8. Separador: ponto e vírgula (;). Cada arquivo contém uma categoria de dados.\r\n"+
                "Os identificadores permitem relacionar clientes, equipamentos, ordens e pagamentos entre os arquivos.\r\n"+
                "Datas e horários incluem o fuso quando disponível. Campos vazios indicam dados não informados.\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();zip.finish();return bytes.toByteArray();
        }catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
    });}
    private void writeCsv(java.util.zip.ZipOutputStream zip,String name,String sql,UUID tenant) throws java.io.IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name+".csv"));
        byte[] csv=jdbc.query(sql,rs->{
            var metadata=rs.getMetaData();
            var columns=new java.util.ArrayList<String>();
            for(int i=1;i<=metadata.getColumnCount();i++) columns.add(metadata.getColumnLabel(i));
            var rows=new java.util.ArrayList<java.util.List<Object>>();
            while(rs.next()) {
                var row=new java.util.ArrayList<Object>();
                for(int i=1;i<=metadata.getColumnCount();i++) row.add(rs.getObject(i));
                rows.add(row);
            }
            return CompanyCsv.render(columns,rows);
        },tenant);
        zip.write(csv);zip.closeEntry();
    }
}
