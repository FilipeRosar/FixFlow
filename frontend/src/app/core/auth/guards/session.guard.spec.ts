import { TestBed } from "@angular/core/testing";
import { provideHttpClient } from "@angular/common/http";
import {
  provideHttpClientTesting,
  HttpTestingController,
} from "@angular/common/http/testing";
import { provideRouter, Router } from "@angular/router";
import { RouterTestingHarness } from "@angular/router/testing";
import { routes } from "../../../app.routes";

describe("Protected workspace", () => {
  it("restores the session on reload before allowing navigation", async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl("/inicio");
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    TestBed.inject(HttpTestingController)
      .expectOne("/api/v1/auth/refresh")
      .flush({
        accessToken: "jwt",
        expiresIn: 900,
        userId: "u",
        tenantId: "t",
        role: "OWNER",
      });
    await navigation;
    expect(TestBed.inject(Router).url).toBe("/inicio");
    expect(harness.routeNativeElement?.textContent).toContain("Bem-vindo");
    TestBed.inject(HttpTestingController).verify();
  });
});
