export interface Facility {
  id: number
  name: string
  address?: string
  description?: string
  status: string
  createdAt: string
  documentCount: number
  systemCount: number
  equipmentCount: number
  equipmentUnits: number
  errorCount: number
}

export interface EngineeringSystem {
  id: number
  name: string
  code?: string
  description?: string
  documentCount?: number
}

export interface DocumentType {
  id: number
  name: string
  code: string
}

export interface Doc {
  id: number
  facilityId: number
  engineeringSystemId?: number
  documentTypeId?: number
  name: string
  originalFilename: string
  mimeType?: string
  size: number
  pageCount?: number
  status: string
  errorMessage?: string
  actualityStatus: string
  createdAt: string
}

export interface EquipmentSourceRef {
  documentId: number
  documentName: string
  pageNumber?: number
}

export interface Equipment {
  id: number
  facilityId: number
  engineeringSystemId?: number
  manufacturer?: string
  name?: string
  model?: string
  modification?: string
  quantity: number
  unit: string
  location?: string
  comment?: string
  status: string
  sources?: EquipmentSourceRef[]
}

export interface Chat {
  id: number
  facilityId?: number
  engineeringSystemId?: number
  documentId?: number
  title?: string
  createdAt: string
}

export interface AnswerSource {
  documentId: number
  documentName: string
  pageNumber?: number
  chunkId?: number
  snippet?: string
}

export interface ChatMessageDto {
  id: number
  role: 'user' | 'assistant'
  content: string
  createdAt: string
  sources: AnswerSource[]
}

export interface NormativeSourcebook {
  id: number
  name: string
  code?: string
  originalFilename?: string
  pageCount?: number
  rateCount: number
  status: string
  errorMessage?: string
  createdAt: string
}

export interface NormativeRate {
  id: number
  sourcebookId: number
  code: string
  name: string
  unit?: string
  workComposition?: string
  laborCost?: number
  machineCost?: number
  machineLabor?: number
  materialCost?: number
  laborHours?: number
  pageNumber?: number
}

export interface NormativeMatch {
  rate: NormativeRate
  reason?: string
}

export interface NormativeMatchResult {
  matches: NormativeMatch[]
  candidates: NormativeRate[]
  aiUsed: boolean
}

export interface PkmDocument {
  id: number
  name: string
  systemType?: string
  originalFilename?: string
  operationCount: number
  status: string
  errorMessage?: string
  createdAt: string
}

export interface PkmOperation {
  id: number
  pkmId: number
  systemType?: string
  position?: number
  category?: string
  operationName: string
  workComposition?: string
  periodicity?: string
  periodicityPerYear?: number
}

export interface Estimate {
  id: number
  facilityId: number
  systemId?: number
  name: string
  status: string
  nrZp: number
  npZp: number
  nrEm: number
  npEm: number
  vat: number
  rtCoefficient: number
  createdAt: string
  updatedAt: string
}

export interface EstimateRowEntity {
  id: number
  estimateId: number
  position?: number
  section?: string
  equipmentId?: number
  equipmentName?: string
  equipmentType?: string
  manufacturer?: string
  operationName?: string
  rateCode?: string
  rateName?: string
  periodicity?: string
  justification?: string
  opsPerYear?: number
  qty?: number
  unitBasis: number
  priceZp?: number
  priceEm?: number
  priceZpm?: number
  priceMr?: number
  correction: number
  laborHours?: number
}

export interface EstimateRowCalc {
  performedPerYear: number
  totalUnits: number
  zp: number; em: number; zpm: number; mr: number
  nr: number; np: number; totalNoVat: number; vat: number; totalWithVat: number
  zpRt: number; emRt: number; zpmRt: number; mrRt: number
  nrRt: number; npRt: number; totalNoVatRt: number; vatRt: number; totalWithVatRt: number
  laborHoursTotal: number
}

export interface EstimateRowView {
  row: EstimateRowEntity
  calc: EstimateRowCalc
}

export interface EstimateTotals {
  totalNoVat: number; vat: number; totalWithVat: number
  totalNoVatRt: number; vatRt: number; totalWithVatRt: number
  laborHoursTotal: number
}

export interface EstimateView {
  estimate: Estimate
  rows: EstimateRowView[]
  totals: EstimateTotals
}

export interface Dashboard {
  facilityCount: number
  documentCount: number
  equipmentCount: number
  errorCount: number
  recentFacilities: Facility[]
  recentDocuments: Doc[]
  recentChats: Chat[]
}
