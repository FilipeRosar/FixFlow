export interface Session {
  accessToken: string;
  tokenType: "Bearer";
  expiresIn: number;
  userId: string;
  tenantId: string;
  role: string;
}
export interface LoginRequest {
  email: string;
  password: string;
  tenantId?: string;
}
export interface RegistrationRequest {
  document: string;
  address: {
    street: string;
    number: string;
    complement: string;
    neighborhood: string;
    city: string;
    state: string;
    postalCode: string;
  };
  phone: string;
  companyName: string;
  firstName: string;
  lastName: string;
  email: string;
  password: string;
}
export interface RegisteredAccount {
  userId: string;
  tenantId: string;
  role: string;
}
export interface Membership {
  userId: string;
  tenantId: string;
  role: string;
}
