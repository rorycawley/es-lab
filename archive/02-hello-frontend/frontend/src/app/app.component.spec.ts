import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  let fixture: ComponentFixture<AppComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AppComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('shows checking while request is in flight', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne('/api/health');
    expect(fixture.nativeElement.textContent).toContain('Checking');
  });

  it('shows healthy when backend returns status ok', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne('/api/health').flush({ status: 'ok', version: '0.1.0' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Project is confirmed to be healthy');
  });

  it('shows not healthy on http error', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne('/api/health').error(new ProgressEvent('error'));
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Project not healthy');
  });

  it('re-checks with a new endpoint when check() is called', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne('/api/health').flush({ status: 'ok', version: '0.1.0' });
    await fixture.whenStable();

    fixture.componentInstance.endpointForm.path().value.set('/api/other');
    fixture.componentInstance.check();
    await fixture.whenStable();

    const req = httpMock.expectOne('/api/other');
    req.flush({ status: 'ok', version: '0.1.0' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Project is confirmed to be healthy');
  });

  it('re-checks the same endpoint when check() is called again', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne('/api/health').flush({ status: 'ok', version: '0.1.0' });
    await fixture.whenStable();

    fixture.componentInstance.check();
    await fixture.whenStable();

    httpMock.expectOne('/api/health').flush({ status: 'ok', version: '0.1.0' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Project is confirmed to be healthy');
  });
});
