export interface Session {
  accessToken: string;
  tokenType: 'Bearer';
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
  phone: string;
  address: {
    street: string;
    number: string;
    complement: string;
    neighborhood: string;
    city: string;
    state: string;
    postalCode: string;
  };
  companyName: string;
  fullName: string;
  email: string;
  password: string;
  plan: string;
}
export interface RegisteredAccount {
  userId: string;
  tenantId: string;
  role: string;
}
export interface Membership {
  onboardingRequired?: boolean;
  logoDataUrl?: string | null;
  companyName: string;
  userId: string;
  tenantId: string;
  role: string;
}
