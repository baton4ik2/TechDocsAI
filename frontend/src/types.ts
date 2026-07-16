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
}

export interface ChatMessageDto {
  id: number
  role: 'user' | 'assistant'
  content: string
  createdAt: string
  sources: AnswerSource[]
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
