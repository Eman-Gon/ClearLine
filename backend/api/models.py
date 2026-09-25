from typing import Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra='forbid')


class Consent(StrictModel):
    recording: bool
    cloud_export: bool = False


class CreateSession(StrictModel):
    profile_id: UUID | None = None
    consent: Consent
    data_origin: Literal['consented_demo'] = 'consented_demo'
    recording_task: Literal['check_in'] = 'check_in'
    pairing_code: str = Field(min_length=1, max_length=200)


class ResourceRequest(StrictModel):
    category: Literal['caregiver support groups', 'respite care', 'caregiver education', 'caregiver_support', 'respite_care', 'caregiver_education']
    city: str = Field(min_length=1, max_length=100, pattern=r"^[\w .,()'’-]+$")
    approved: Literal[True]


class UserInput(StrictModel):
    input_revision: int = Field(ge=0)
    clip_id: UUID | None = None
    transcript: str | None = Field(default=None, min_length=1, max_length=10000)
    city: str | None = Field(default=None, min_length=1, max_length=100, pattern=r"^[\w .,()'’-]+$")
    answer: str | None = Field(default=None, min_length=1, max_length=500)

    @model_validator(mode='after')
    def one_change(self):
        if sum(v is not None for v in (self.transcript, self.city, self.answer)) != 1:
            raise ValueError('Provide exactly one transcript, city, or answer.')
        if (self.transcript is not None) != (self.clip_id is not None):
            raise ValueError('A transcript correction requires clip_id.')
        return self
