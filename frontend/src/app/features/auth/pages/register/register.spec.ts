import { TestBed } from "@angular/core/testing";
import { provideHttpClient, withInterceptors } from "@angular/common/http";
import {
  provideHttpClientTesting,
  HttpTestingController,
} from "@angular/common/http/testing";
import { provideRouter, Router } from "@angular/router";
import { Register } from "./register";
import { sessionInterceptor } from "../../../../core/auth/interceptors/session.interceptor";

describe("Owner registration screen", () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [Register],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function fill(page: HTMLElement, name: string, value: string) {
    const input = page.querySelector<HTMLInputElement>(`#${name}`)!;
    input.value = value;
    input.dispatchEvent(new Event("input"));
  }
  function populated() {
    const fixture = TestBed.createComponent(Register);
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    fill(page, "companyName", "Oficina Teste");
    fill(page, "firstName", "Owner");
    fill(page, "lastName", "Teste");
    fill(page, "email", "owner@example.com");
    fill(page, "password", "Owner1234!");
    fill(page, "confirmPassword", "Owner1234!");
    fill(page, "document", "52998224725");
    fill(page, "street", "Rua Teste");
    fill(page, "number", "123");
    fill(page, "neighborhood", "Centro");
    fill(page, "city", "São Paulo");
    fill(page, "state", "SP");
    fill(page, "postalCode", "01001000");
    fill(page, "phone", "11999999999");
    return { fixture, page };
  }

  it("fills the address on confirmation without submitting and preserves number and complement", () => {
    const { page, fixture } = populated();
    fill(page, "postalCode", "01001-000");
    fill(page, "complement", "Sala 2");
    page.querySelector<HTMLButtonElement>("#confirmPostalCode")!.click();
    fixture.detectChanges();
    expect(
      page.querySelector<HTMLButtonElement>("#confirmPostalCode")!.disabled,
    ).toBe(true);
    TestBed.inject(HttpTestingController).expectNone("/api/contas");
    TestBed.inject(HttpTestingController)
      .expectOne("/api/enderecos/cep/01001000")
      .flush({
        street: "Praça da Sé",
        neighborhood: "Sé",
        city: "São Paulo",
        state: "SP",
      });
    fixture.detectChanges();
    expect(page.querySelector<HTMLInputElement>("#street")!.value).toBe(
      "Praça da Sé",
    );
    expect(page.querySelector<HTMLInputElement>("#neighborhood")!.value).toBe(
      "Sé",
    );
    expect(page.querySelector<HTMLInputElement>("#number")!.value).toBe("123");
    expect(page.querySelector<HTMLInputElement>("#complement")!.value).toBe(
      "Sala 2",
    );
    expect(page.querySelector("#postalCode-status")?.textContent).toContain(
      "Endereço encontrado",
    );
  });

  it("does not request invalid postal codes", () => {
    const { page, fixture } = populated();
    fill(page, "postalCode", "123");
    page.querySelector<HTMLButtonElement>("#confirmPostalCode")!.click();
    fixture.detectChanges();
    expect(page.querySelector("#postalCode-error")?.textContent).toContain(
      "8 dígitos",
    );
    TestBed.inject(HttpTestingController).expectNone((r) =>
      r.url.startsWith("/api/enderecos/cep/"),
    );
  });

  it("keeps manual entry available when the postal code is missing or the service fails", () => {
    const { page, fixture } = populated();
    for (const status of [404, 503]) {
      page.querySelector<HTMLButtonElement>("#confirmPostalCode")!.click();
      TestBed.inject(HttpTestingController)
        .expectOne("/api/enderecos/cep/01001000")
        .flush({}, { status, statusText: "Lookup error" });
      fixture.detectChanges();
      expect(page.querySelector("#postalCode-status")?.textContent).toContain(
        "manualmente",
      );
      expect(page.querySelector<HTMLInputElement>("#street")!.value).toBe(
        "Rua Teste",
      );
      expect(page.querySelector<HTMLInputElement>("#street")!.disabled).toBe(
        false,
      );
    }
  });

  it("ignores a lookup for an old postal code and preserves edits made during a request", () => {
    const { page } = populated();
    page.querySelector<HTMLButtonElement>("#confirmPostalCode")!.click();
    fill(page, "postalCode", "20040002");
    TestBed.inject(HttpTestingController)
      .expectOne("/api/enderecos/cep/01001000")
      .flush({
        street: "Praça da Sé",
        neighborhood: "Sé",
        city: "São Paulo",
        state: "SP",
      });
    expect(page.querySelector<HTMLInputElement>("#street")!.value).toBe(
      "Rua Teste",
    );
    page.querySelector<HTMLButtonElement>("#confirmPostalCode")!.click();
    fill(page, "street", "Minha rua");
    TestBed.inject(HttpTestingController)
      .expectOne("/api/enderecos/cep/20040002")
      .flush({
        street: "Rua da Assembleia",
        neighborhood: "Centro",
        city: "Rio de Janeiro",
        state: "RJ",
      });
    expect(page.querySelector<HTMLInputElement>("#street")!.value).toBe(
      "Minha rua",
    );
    expect(page.querySelector<HTMLInputElement>("#city")!.value).toBe(
      "Rio de Janeiro",
    );
  });

  it("creates the company through the API and sends only non-secret data to the login screen", () => {
    const { page } = populated();
    const navigate = vi
      .spyOn(TestBed.inject(Router), "navigate")
      .mockResolvedValue(true);
    page
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    const request = TestBed.inject(HttpTestingController).expectOne(
      "/api/contas",
    );
    expect(request.request.body).toEqual({
      companyName: "Oficina Teste",
      firstName: "Owner",
      lastName: "Teste",
      email: "owner@example.com",
      password: "Owner1234!",
      document: "52998224725",
      address: {
        street: "Rua Teste",
        number: "123",
        complement: "",
        neighborhood: "Centro",
        city: "São Paulo",
        state: "SP",
        postalCode: "01001000",
      },
      phone: "11999999999",
    });
    request.flush({ userId: "u", tenantId: "t", role: "OWNER" });
    expect(navigate).toHaveBeenCalledWith(["/entrar"], {
      state: { registered: true, email: "owner@example.com", tenantId: "t" },
    });
    expect(page.querySelector<HTMLInputElement>("#password")!.value).toBe("");
  });

  it("blocks mismatched passwords and missing contact details", () => {
    const { page, fixture } = populated();
    fill(page, "confirmPassword", "Different123!");
    page
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    fixture.detectChanges();
    expect(
      page.querySelector("#confirm-password-error")?.textContent,
    ).toContain("iguais");
    TestBed.inject(HttpTestingController).expectNone("/api/contas");
    fill(page, "confirmPassword", "Owner1234!");
    for (const field of ["document", "street", "phone"]) fill(page, field, "");
    page
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    TestBed.inject(HttpTestingController).expectNone("/api/contas");
  });

  it("accepts formatted CNPJ and phone and sends normalized values", () => {
    const { page } = populated();
    fill(page, "document", "11.222.333/0001-81");
    fill(page, "phone", "(11) 99999-9999");
    page
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    const request = TestBed.inject(HttpTestingController).expectOne(
      "/api/contas",
    );
    expect(request.request.body.document).toBe("11222333000181");
    expect(request.request.body.phone).toBe("11999999999");
    expect(request.request.body.confirmPassword).toBeUndefined();
    request.flush({}, { status: 400, statusText: "Bad Request" });
  });

  it("requires eight characters, uppercase, lowercase and a number", () => {
    const { page } = populated();
    for (const password of ["Abc1234", "abcdefgh1", "ABCDEFGH1", "Abcdefgh"]) {
      fill(page, "password", password);
      fill(page, "confirmPassword", password);
      page
        .querySelector("form")!
        .dispatchEvent(
          new Event("submit", { bubbles: true, cancelable: true }),
        );
      TestBed.inject(HttpTestingController).expectNone("/api/contas");
    }
    fill(page, "password", "Abcdef12");
    fill(page, "confirmPassword", "Abcdef12");
    page
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    TestBed.inject(HttpTestingController)
      .expectOne("/api/contas")
      .flush({}, { status: 400, statusText: "Bad Request" });
  });

  it("toggles password visibility without submitting or changing its value", () => {
    const { page, fixture } = populated();
    for (const id of ["password", "confirmPassword"]) {
      const input = page.querySelector<HTMLInputElement>(`#${id}`)!;
      const button = page.querySelector<HTMLButtonElement>(
        `button[aria-controls="${id}"]`,
      )!;
      expect(input.type).toBe("password");
      button.click();
      fixture.detectChanges();
      expect(input.type).toBe("text");
      expect(input.value).toBe("Owner1234!");
      expect(button.getAttribute("aria-label")).toBe("Ocultar senha");
      button.click();
      fixture.detectChanges();
      expect(input.type).toBe("password");
    }
    TestBed.inject(HttpTestingController).expectNone("/api/contas");
  });

  it("shows an actionable duplicate-email message", async () => {
    const { page, fixture } = populated();
    page
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    TestBed.inject(HttpTestingController)
      .expectOne("/api/contas")
      .flush({}, { status: 409, statusText: "Conflict" });
    await fixture.whenStable();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain(
      "já está cadastrado",
    );
  });

  it("does not submit passwords over the BCrypt byte limit", () => {
    const { page, fixture } = populated();
    fill(page, "password", "á".repeat(40));
    page
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectNone("/api/contas");
    expect(page.querySelector("#password-error")?.textContent).toContain(
      "reduza o tamanho",
    );
  });
});
