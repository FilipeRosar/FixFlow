import { TestBed } from "@angular/core/testing";
import { provideHttpClient } from "@angular/common/http";
import {
  provideHttpClientTesting,
  HttpTestingController,
} from "@angular/common/http/testing";
import { provideRouter, Router } from "@angular/router";
import { Register } from "./register";

describe("Owner registration screen", () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [Register],
      providers: [
        provideRouter([]),
        provideHttpClient(),
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
    fill(page, "fullName", "Owner Teste");
    fill(page, "email", "owner@example.com");
    fill(page, "password", "Owner1234!");
    return { fixture, page };
  }

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
      fullName: "Owner Teste",
      email: "owner@example.com",
      password: "Owner1234!",
    });
    request.flush({ userId: "u", tenantId: "t", role: "OWNER" });
    expect(navigate).toHaveBeenCalledWith(["/entrar"], {
      state: { registered: true, email: "owner@example.com", tenantId: "t" },
    });
    expect(page.querySelector<HTMLInputElement>("#password")!.value).toBe("");
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
      "72 bytes",
    );
  });
});
