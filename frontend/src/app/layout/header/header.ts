import { Component, inject } from "@angular/core";
import { RouterLink } from "@angular/router";
import { SessionService } from "../../core/auth/data-access/session.service";
@Component({
  selector: "app-header",
  imports: [RouterLink],
  templateUrl: "./header.html",
})
export class Header {
  protected readonly session = inject(SessionService).session;
}
