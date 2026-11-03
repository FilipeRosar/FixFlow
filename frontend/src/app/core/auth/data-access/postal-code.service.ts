import { Injectable, inject } from "@angular/core";
import { HttpClient } from "@angular/common/http";

export interface PostalCodeAddress {
  street: string;
  neighborhood: string;
  city: string;
  state: string;
}

@Injectable({ providedIn: "root" })
export class PostalCodeService {
  private readonly http = inject(HttpClient);
  lookup(postalCode: string) {
    return this.http.get<PostalCodeAddress>(
      `/api/enderecos/cep/${postalCode}`,
      { timeout: 10000 },
    );
  }
}
