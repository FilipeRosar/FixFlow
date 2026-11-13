package br.com.fixflow.modules.tenants.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** CSV for Brazilian spreadsheets: UTF-8 BOM, semicolons, quoted cells and formula neutralization. */
public final class CompanyCsv {
    private CompanyCsv() { }
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("id","Identificador"),Map.entry("tenant_id","Identificador da empresa"),
        Map.entry("name","Nome"),Map.entry("document","CPF/CNPJ"),Map.entry("phone","Telefone"),
        Map.entry("street","Rua"),Map.entry("number","Número"),Map.entry("complement","Complemento"),
        Map.entry("neighborhood","Bairro"),Map.entry("city","Cidade"),Map.entry("state","UF"),Map.entry("postal_code","CEP"),
        Map.entry("customer_id","Identificador do cliente"),Map.entry("equipment_id","Identificador do equipamento"),
        Map.entry("work_order_id","Identificador da ordem de serviço"),Map.entry("part_id","Identificador da peça"),
        Map.entry("quote_id","Identificador do orçamento"),Map.entry("item_id","Identificador do item"),
        Map.entry("created_at","Criado em"),Map.entry("updated_at","Atualizado em"),Map.entry("deleted_at","Excluído em"),
        Map.entry("type","Tipo"),Map.entry("value","Valor"),Map.entry("label","Descrição do contato"),Map.entry("position","Posição"),
        Map.entry("category","Categoria"),Map.entry("brand","Marca"),Map.entry("model","Modelo"),Map.entry("serial_number","Número de série"),
        Map.entry("status","Situação"),Map.entry("description","Descrição"),Map.entry("quantity","Quantidade"),
        Map.entry("unit_price","Preço unitário"),Map.entry("unit_cost","Custo unitário"),Map.entry("cost","Custo"),
        Map.entry("sale_price","Preço de venda"),Map.entry("quantity_on_hand","Saldo em estoque"),Map.entry("min_quantity","Estoque mínimo"),
        Map.entry("active","Ativo"),Map.entry("unit","Unidade"),Map.entry("sku","Código da peça"),
        Map.entry("reported_issue","Defeito informado"),Map.entry("physical_condition","Estado físico"),
        Map.entry("received_accessories","Acessórios recebidos"),Map.entry("notes","Observações"),
        Map.entry("amount","Valor"),Map.entry("method","Forma de pagamento"),Map.entry("paid_at","Pago em"),
        Map.entry("created_by","Registrado por"),Map.entry("recorded_by","Registrado por"),Map.entry("removed_at","Removido em"),
        Map.entry("version","Versão"),Map.entry("total","Total"),Map.entry("subtotal","Subtotal"),Map.entry("discount","Desconto"),
        Map.entry("valid_until","Válido até"),Map.entry("approved_at","Aprovado em"),Map.entry("delivered_at","Entregue em"));
    public static byte[] render(List<String> columns,List<List<Object>> rows) {
        var out=new StringBuilder("\ufeff");
        line(out,columns.stream().map(c->(Object)LABELS.getOrDefault(c,c.replace('_',' '))).toList());
        for(var row:rows) line(out,row);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void line(StringBuilder out,List<Object> cells) {
        for(int i=0;i<cells.size();i++) {
            if(i>0) out.append(';');
            Object value=cells.get(i);
            String text=value==null?"":value instanceof Boolean b?(b?"Sim":"Não"):value.toString();
            if(value instanceof java.math.BigDecimal) text=text.replace('.',',');
            // Untrusted text must remain text even when opened directly in a spreadsheet.
            if(value instanceof String && (!text.equals(text.stripLeading()) || text.matches("^[=+@-][\\s\\S]*"))) text="'"+text;
            out.append('"').append(text.replace("\"","\"\"")).append('"');
        }
        out.append("\r\n");
    }
}
