import { TestBed } from "@angular/core/testing";
import { provideHttpClient } from "@angular/common/http";
import {
  provideHttpClientTesting,
  HttpTestingController,
} from "@angular/common/http/testing";
import { provideRouter, Router } from "@angular/router";
import { Login } from "./login";
import { SessionService } from "../../../../core/auth/data-access/session.service";

describe("Login screen", () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function filled() {
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    for (const [id, value] of [
      ["email", "owner@example.com"],
      ["password", "Owner1234!"],
    ]) {
      const input = page.querySelector<HTMLInputElement>(`#${id}`)!;
      input.value = value;
      input.dispatchEvent(new Event("input"));
    }
    return { fixture, page };
  }
  it("enters the protected area after receiving an access token", () => {
    const { page } = filled();
    const navigate = vi
      .spyOn(TestBed.inject(Router), "navigate")
      .mockResolvedValue(true);
    page
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { cancelable: true }));
    TestBed.inject(HttpTestingController)
      .expectOne("/api/v1/auth/login")
      .flush({
        accessToken: "jwt",
        tokenType: "Bearer",
        expiresIn: 900,
        userId: "u",
        tenantId: "t",
        role: "OWNER",
      });
    expect(TestBed.inject(SessionService).session()?.role).toBe("OWNER");
    expect(navigate).toHaveBeenCalledWith(["/inicio"]);
    expect(page.querySelector<HTMLInputElement>("#password")!.value).toBe("");
  });
  it("uses a generic error for invalid or locked accounts and allows another attempt", async () => {
    const { page, fixture } = filled();
    page
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { cancelable: true }));
    TestBed.inject(HttpTestingController)
      .expectOne("/api/v1/auth/login")
      .flush({}, { status: 401, statusText: "Unauthorized" });
    await fixture.whenStable();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain(
      "aguarde",
    );
    expect(
      page.querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled,
    ).toBe(false);
  });
});
